/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jefferyyuan.codeexample.nutch.parse.js.treenodes;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.nutch.indexer.NutchDocument;
import org.apache.nutch.parse.HTMLMetaTags;
import org.apache.nutch.parse.Outlink;
import org.apache.nutch.parse.Parse;
import org.apache.nutch.parse.ParseFilter;
import org.apache.nutch.parse.ParseStatusCodes;
import org.apache.nutch.parse.ParseStatusUtils;
import org.apache.nutch.parse.Parser;
import org.apache.nutch.storage.ParseStatus;
import org.apache.nutch.storage.WebPage;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.TableUtil;
import org.apache.oro.text.regex.MalformedPatternException;
import org.apache.oro.text.regex.MatchResult;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.PatternCompiler;
import org.apache.oro.text.regex.PatternMatcher;
import org.apache.oro.text.regex.PatternMatcherInput;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * This class is a heuristic link extractor for JavaScript files and code
 * snippets. The general idea of a two-pass regex matching comes from Heritrix.
 * Parts of the code come from OutlinkExtractor.java by Stephan Strittmatter.
 * 
 * @author Andrzej Bialecki &lt;ab@getopt.org&gt;
 */
public class ExtJSParseFilter implements ParseFilter, Parser {
  public static final Logger LOG = LoggerFactory
      .getLogger(ExtJSParseFilter.class);

  private static final int MAX_TITLE_LEN = 80;
  private static final String DEFAULT_FILE_INCLUDE_PATTERN_STR = "*.js";
  private static final String ABSOLUTE_URL_PATTERN_STR = "^[http|https|www].*";
  private static Pattern fileIncludePath, absoluteURLPpattern, outlinkPattern;
  private Configuration conf;

  /**
   * Set the {@link Configuration} object
   */
  public void setConf(Configuration conf) {
    this.conf = conf;
    PatternCompiler patternCompiler = new Perl5Compiler();

    try {
      String str = conf.get("ext.js.file.include.pattern",
          DEFAULT_FILE_INCLUDE_PATTERN_STR);
      fileIncludePath = patternCompiler.compile(str,
          Perl5Compiler.READ_ONLY_MASK | Perl5Compiler.SINGLELINE_MASK);
      str = conf.get("ext.js.absolute.url.pattern", ABSOLUTE_URL_PATTERN_STR);
      absoluteURLPpattern = patternCompiler.compile(str,
          Perl5Compiler.CASE_INSENSITIVE_MASK | Perl5Compiler.READ_ONLY_MASK
              | Perl5Compiler.SINGLELINE_MASK);

      str = conf.get("ext.js.extract.outlink.pattern");
      if (!StringUtils.isBlank(str)) {
        outlinkPattern = patternCompiler.compile(str,
            Perl5Compiler.READ_ONLY_MASK | Perl5Compiler.MULTILINE_MASK);
      }
    } catch (MalformedPatternException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean shouldHandlePage(WebPage page) {
    boolean shouldHandle = false;
    String url = TableUtil.toString(page.getBaseUrl());
    PatternMatcher matcher = new Perl5Matcher();
    if (matcher.matches(url, fileIncludePath)) {
      shouldHandle = true;
    }
    return shouldHandle;
  }

  /**
   * @param baseUrl
   *          , is always a folder path: http://... (/tree_nodes.js) is removed.
   * @param path
   * @return
   * @throws MalformedPatternException
   */
  private static String toAbsolutePath(String baseUrl, String path)
      throws MalformedPatternException {
    PatternMatcher matcher = new Perl5Matcher();
    boolean isAbsolute = false;
    if (matcher.matches(path, absoluteURLPpattern)) {
      isAbsolute = true;
    }

    if (isAbsolute) {
      return path;
    }
    while (true) {
      if (!path.startsWith("../")) {
        break;
      }
      baseUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/'));
      path = path.substring(3);
    }
    // now relativePath is foldera/fileb, no /

    return baseUrl + "/" + path;
  }

  /**
   * This method extracts URLs from literals embedded in JavaScript.
   */
  public static Outlink[] getJSLinks(String plainText, String anchor,
      String base) {
    long start = System.currentTimeMillis();
  
    // the base is always absolute path: http://.../tree_nodes.js, change it to
    // folder
    base = base.substring(0, base.lastIndexOf('/'));
    final List<Outlink> outlinks = new ArrayList<Outlink>();
    URL baseURL = null;
  
    try {
      baseURL = new URL(base);
    } catch (Exception e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("error assigning base URL", e);
      }
    }
  
    try {
      final PatternMatcher matcher = new Perl5Matcher();
      final PatternMatcherInput input = new PatternMatcherInput(plainText);
  
      MatchResult result;
      String url;
  
      // loop the matches
      while (matcher.contains(input, outlinkPattern)) {
        // if this is taking too long, stop matching
        // (SHOULD really check cpu time used so that heavily loaded systems
        // do not unnecessarily hit this limit.)
        if (System.currentTimeMillis() - start >= 60000L) {
          if (LOG.isWarnEnabled()) {
            LOG.warn("Time limit exceeded for getOutLinks");
          }
          break;
        }
        result = matcher.getMatch();
        url = result.group(1);
        // See if candidate URL is parseable. If not, pass and move on to
        // the next match.
        try {
          url = new URL(toAbsolutePath(base, url)).toString();
          LOG.info("Extension added: " + url + " and baseURL " + baseURL);
        } catch (MalformedURLException ex) {
          // if (LOG.isTraceEnabled()) {
          // LOG.trace(" - failed URL parse '" + url + "' and baseURL '"
          // + baseURL + "'", ex);
          // }
          LOG.info("Extension - failed URL parse '" + url + "' and baseURL '"
              + baseURL + "'", ex);
          continue;
        }
        try {
          outlinks.add(new Outlink(url.toString(), anchor));
        } catch (MalformedURLException mue) {
          LOG.warn("Extension Invalid url: '" + url + "', skipping.");
        }
      }
    } catch (Exception ex) {
      // if the matcher fails (perhaps a malformed URL) we just log it and move
      // on
      if (LOG.isErrorEnabled()) {
        LOG.error("getOutlinks", ex);
      }
    }
  
    final Outlink[] retval;
    // create array of the Outlinks
    if (outlinks != null && outlinks.size() > 0) {
      retval = outlinks.toArray(new Outlink[0]);
    } else {
      retval = new Outlink[0];
    }
  
    return retval;
  }

  @Override
  public Parse filter(String url, WebPage page, Parse parse,
      HTMLMetaTags metaTags, DocumentFragment doc) {
    if (shouldHandlePage(page)) {
      ArrayList<Outlink> outlinks = new ArrayList<Outlink>();

      walk(doc, parse, metaTags, url, outlinks);
      if (outlinks.size() > 0) {
        Outlink[] old = parse.getOutlinks();
        String title = parse.getTitle();
        List<Outlink> list = Arrays.asList(old);
        outlinks.addAll(list);
        ParseStatus status = parse.getParseStatus();
        String text = parse.getText();
        Outlink[] newlinks = outlinks.toArray(new Outlink[outlinks.size()]);
        return new Parse(text, title, newlinks, status);
      }
    }
    return parse;
  }

  private void walk(Node n, Parse parse, HTMLMetaTags metaTags, String base,
      List<Outlink> outlinks) {
    if (n instanceof Element) {
      String name = n.getNodeName();
      if (name.equalsIgnoreCase("script")) {
        @SuppressWarnings("unused")
        String lang = null;
        Node lNode = n.getAttributes().getNamedItem("language");
        if (lNode == null)
          lang = "javascript";
        else
          lang = lNode.getNodeValue();
        StringBuilder script = new StringBuilder();
        NodeList nn = n.getChildNodes();
        if (nn.getLength() > 0) {
          for (int i = 0; i < nn.getLength(); i++) {
            if (i > 0)
              script.append('\n');
            script.append(nn.item(i).getNodeValue());
          }
          Outlink[] links = getJSLinks(script.toString(), "", base);
          if (links != null && links.length > 0)
            outlinks.addAll(Arrays.asList(links));
          // no other children of interest here, go one level up.
          return;
        }
      } else {
        // process all HTML 4.0 events, if present...
        NamedNodeMap attrs = n.getAttributes();
        int len = attrs.getLength();
        for (int i = 0; i < len; i++) {
          Node anode = attrs.item(i);
          Outlink[] links = null;
          if (anode.getNodeName().startsWith("on")) {
            links = getJSLinks(anode.getNodeValue(), "", base);
          } else if (anode.getNodeName().equalsIgnoreCase("href")) {
            String val = anode.getNodeValue();
            if (val != null && val.toLowerCase().indexOf("javascript:") != -1) {
              links = getJSLinks(val, "", base);
            }
          }
          if (links != null && links.length > 0)
            outlinks.addAll(Arrays.asList(links));
        }
      }
    }
    NodeList nl = n.getChildNodes();
    for (int i = 0; i < nl.getLength(); i++) {
      walk(nl.item(i), parse, metaTags, base, outlinks);
    }
  }

  @Override
  public Parse getParse(String url, WebPage page) {
    if (!shouldHandlePage(page)) {
      return ParseStatusUtils.getEmptyParse(
          ParseStatusCodes.FAILED_INVALID_FORMAT, "Content not JavaScript: '"
              + TableUtil.toString(page.getContentType()) + "'", getConf());
    }
    String script = new String(page.getContent().array());
    Outlink[] outlinks = getJSLinks(script, "", url);
    if (outlinks == null)
      outlinks = new Outlink[0];
    // Title? use the first line of the script...
    String title;
    int idx = script.indexOf('\n');
    if (idx != -1) {
      if (idx > MAX_TITLE_LEN)
        idx = MAX_TITLE_LEN;
      title = script.substring(0, idx);
    } else {
      idx = Math.min(MAX_TITLE_LEN, script.length());
      title = script.substring(0, idx);
    }
    Parse parse = new Parse(script, title, outlinks,
        ParseStatusUtils.STATUS_SUCCESS);
    return parse;
  }

  /**
   * Main method which can be run from command line with the plugin option. The
   * method takes two arguments e.g. o.a.n.parse.js.JSParseFilter file.js
   * baseURL
   * 
   * @param args
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println(CVTreeNodesJSParseFilter.class.getName()
          + " file.js baseURL");
      return;
    }
    InputStream in = new FileInputStream(args[0]);
    BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
    try {
      StringBuilder sb = new StringBuilder();
      String line = null;
      while ((line = br.readLine()) != null)
        sb.append(line + "\n");
      CVTreeNodesJSParseFilter parseFilter = new CVTreeNodesJSParseFilter();
      parseFilter.setConf(NutchConfiguration.create());
      Outlink[] links = getJSLinks(sb.toString(), "", args[1]);
      System.out.println("Outlinks extracted: " + links.length);
      for (int i = 0; i < links.length; i++)
        System.out.println(" - " + links[i]);
    } finally {
      br.close();
    }

  }

  /**
   * Get the {@link Configuration} object
   */
  public Configuration getConf() {
    return this.conf;
  }

  /**
   * Gets all the fields for a given {@link WebPage} Many datastores need to
   * setup the mapreduce job by specifying the fields needed. All extensions
   * that work on WebPage are able to specify what fields they need.
   */
  @Override
  public Collection<WebPage.Field> getFields() {
    return null;
  }

}
