/**
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

package org.apache.solr.handler.admin;

import org.apache.commons.io.IOUtils;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.RawResponseWriter;
import org.apache.solr.response.SolrQueryResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * This handler allows you to push files to the solr home.
 * 
 * <pre>
 * &lt;requestHandler name="/pushfile" class="org.apache.solr.handler.admin.PushFileRequestHandler" /&gt;
 *   &lt;lst name="defaults"&gt;
 *    &lt;str name="echoParams"&gt;explicit&lt;/str&gt;
 *   &lt;/lst&gt;
 *   &lt;lst name="invariants"&gt;
 *    &lt;str name="hidden"&gt;synonyms.txt&lt;/str&gt; 
 *    &lt;str name="hidden"&gt;anotherfile.txt&lt;/str&gt; 
 *   &lt;/lst&gt;
 * &lt;/requestHandler&gt;
 * </pre>
 * 
 * <pre>
 *   http://localhost:8983/solr/pushfile?file=synonyms.txt&contents=synonyms1,synonym2
 * </pre>
 * 
 *
 * @since solr 3.5
 */
public class PushFileRequestHandler extends RequestHandlerBase
{
  public static final String HIDDEN = "hidden";
  
  protected Set<String> hiddenFiles;
  
  private static PushFileRequestHandler instance;
  public PushFileRequestHandler()
  {
    super();
    instance = this; // used so that getFileContents can access hiddenFiles
  }

  @Override
  public void init(NamedList args) {
    super.init( args );

    // Build a list of hidden files
    hiddenFiles = new HashSet<String>();
    if( invariants != null ) {
      String[] hidden = invariants.getParams( HIDDEN );
      if( hidden != null ) {
        for( String s : hidden ) {
          hiddenFiles.add( s.toUpperCase(Locale.ENGLISH) );
        }
      }
    }
  }
  
  public Set<String> getHiddenFiles()
  {
    return hiddenFiles;
  }
  
  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException, InterruptedException 
  {
      pushFileToFileSystem(req, rsp);
  }


  private void pushFileToFileSystem(SolrQueryRequest req, SolrQueryResponse rsp)
      throws IOException {
    File adminFile = null;
    
    final SolrResourceLoader loader = req.getCore().getResourceLoader();
    File configdir = new File( loader.getConfigDir() );
    if (!configdir.exists()) {
      // TODO: maybe we should just open it this way to start with?
      try {
        configdir = new File( loader.getClassLoader().getResource(loader.getConfigDir()).toURI() );
      } catch (URISyntaxException e) {
        throw new SolrException( ErrorCode.FORBIDDEN, "Can not access configuration directory!");
      }
    }
    String fname = req.getParams().get("file", null);
    if( fname == null ) {
      adminFile = configdir;
    }
    else {
      fname = fname.replace( '\\', '/' ); // normalize slashes
      if( hiddenFiles.contains( fname.toUpperCase(Locale.ENGLISH) ) ) {
        throw new SolrException( ErrorCode.FORBIDDEN, "Can not access: "+fname );
      }
      if( fname.indexOf( ".." ) >= 0 ) {
        throw new SolrException( ErrorCode.FORBIDDEN, "Invalid path: "+fname );  
      }
      adminFile = new File( configdir, fname );
    }
    
    // Make sure the file exists, is readable and is not a hidden file
    if( !adminFile.exists() ) {
      throw new SolrException( ErrorCode.BAD_REQUEST, "Can not find: "+adminFile.getName() 
          + " ["+adminFile.getAbsolutePath()+"]" );
    }
    if( !adminFile.canRead() || adminFile.isHidden() ) {
      throw new SolrException( ErrorCode.BAD_REQUEST, "Can not show: "+adminFile.getName() 
          + " ["+adminFile.getAbsolutePath()+"]" );
    }
    String contents = req.getParams().get("contents", null); 
    // Show a directory listing
    if(contents != null){
    	FileOutputStream fos = new FileOutputStream(adminFile, false);
        fos.write(contents.getBytes());
        fos.close();
        rsp.add(RawResponseWriter.CONTENT_TYPE_TEXT_UTF8, contents);
    }
    else {
      // Include the file contents
      //The file logic depends on RawResponseWriter, so force its use.
      ModifiableSolrParams params = new ModifiableSolrParams( req.getParams() );
      params.set( CommonParams.WT, "raw" );
      req.setParams(params);

      ContentStreamBase content = new ContentStreamBase.FileStream( adminFile );
      rsp.add(RawResponseWriter.CONTENT, content);
    }
    rsp.setHttpCaching(false);
  }
  
  /**
   * This is a utility function that lets you get the contents of an admin file
   * 
   * It is only used so that we can get rid of "/admin/get-file.jsp" and include
   * "admin-extra.html" in "/admin/index.html" using jsp scriptlets
   */
  public static String getFileContents(SolrCore core, String path )
  {
    if( instance != null && instance.hiddenFiles != null ) {
      if( instance.hiddenFiles.contains( path ) ) {
        return ""; // ignore it...
      }
    }
    InputStream input = null;
    try {
      input = core.getResourceLoader().openResource(path);
      return IOUtils.toString( input, "UTF-8" );
    } catch( Exception ex ) {
    } finally {
      IOUtils.closeQuietly(input);
    }
    return "";
  }

  //////////////////////// SolrInfoMBeans methods //////////////////////

  @Override
  public String getDescription() {
    return "Admin Get File -- view config files directly";
  }

  @Override
  public String getVersion() {
      return "$Revision$";
  }

  @Override
  public String getSourceId() {
    return "$Id$";
  }

  @Override
  public String getSource() {
    return "$URL$";
  }
}
