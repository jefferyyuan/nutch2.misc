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

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import junit.framework.TestCase;

import org.apache.hadoop.conf.Configuration;
import org.apache.nutch.parse.Outlink;
import org.apache.nutch.parse.Parse;
import org.apache.nutch.parse.ParseException;
import org.apache.nutch.parse.ParseUtil;
import org.apache.nutch.protocol.ProtocolException;
import org.apache.nutch.storage.WebPage;
import org.apache.nutch.util.MimeUtil;
import org.apache.nutch.util.NutchConfiguration;
import org.junit.Test;

/**
 * JUnit test case for {@link ExtJSParseFilter} which tests 1. That 5 outlinks are
 * extracted from JavaScript snippets embedded in HTML 2. That X outlinks are
 * extracted from a pure JavaScript file (this is temporarily disabled)
 * 
 * @author lewismc
 */

public class TestJSParseFilter extends TestCase {

  private String fileSeparator = System.getProperty("file.separator");

  // This system property is defined in ./src/plugin/build-plugin.xml
  private String sampleDir = System.getProperty("test.data", ".");

  // Make sure sample files are copied to "test.data" as specified in
  // ./src/plugin/parse-js/build.xml during plugin compilation.
  private String[] sampleFiles = { "parse_pure_js_test.js",
      "parse_embedded_js_test.html" };

  private Configuration conf;

  public TestJSParseFilter(String name) {
    super(name);
  }

  protected void setUp() {
    conf = NutchConfiguration.create();
    conf.set("file.content.limit", "-1");
  }

  protected void tearDown() {
  }

  public Outlink[] getOutlinks(String[] sampleFiles) throws ProtocolException,
      ParseException, IOException {
    String urlString;
    Parse parse;

    urlString = "file:" + sampleDir + fileSeparator + sampleFiles;
    File file = new File(urlString);
    byte[] bytes = new byte[(int) file.length()];
    DataInputStream dip = new DataInputStream(new FileInputStream(file));
    dip.readFully(bytes);
    dip.close();

    WebPage page = new WebPage();
    page.setBaseUrl(new Utf8(urlString));
    page.setContent(ByteBuffer.wrap(bytes));
    MimeUtil mutil = new MimeUtil(conf);
    String mime = mutil.getMimeType(file);
    page.setContentType(new Utf8(mime));

    parse = new ParseUtil(conf).parse(urlString, page);
    return parse.getOutlinks();
  }

  @Test
  public void testOutlinkExtraction() throws ProtocolException, ParseException,
      IOException {
    String[] filenames = new File(sampleDir).list();
    for (int i = 0; i < filenames.length; i++) {
      if (filenames[i].endsWith(".js") == true) {
        assertEquals("number of outlinks in .js test file should be 5", 5,
            getOutlinks(sampleFiles));
        // temporarily disabled as a suitable pure JS file could not be be
        // found.
        // } else {
        // assertEquals("number of outlinks in .html file should be X", 5,
        // getOutlinks(sampleFiles));
      }
    }
  }

  @Test
  public void testGetJSLinks() {
    String plainText = "[\"Configuration\", \"fs_archive_exchange/web_console/config.htm\", \"main\"],[\"Configuration\", \"../../products/fs_archive_exchange/web_console/config.htm\", \"main\"], \r\n [\"Quick Start Guide - PDF Version\", \"../../pdf/one_pass_exchange.pdf\", \"_blank\"],";
    Outlink[] links = ExtJSParseFilter
        .getJSLinks(plainText, "",
            "http://localhost:8080/hello/f1/f2/tree_nodes.js");
    if (links != null) {
      System.out.println(links.length);
      for (Outlink link : links) {
        System.out.println(link.toString());
      }

    }
  }

}