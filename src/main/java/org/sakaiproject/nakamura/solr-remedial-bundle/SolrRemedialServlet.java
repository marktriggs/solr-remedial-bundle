package org.sakaiproject.nakamura.solrremedialbundle;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

import javax.jcr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.sling.SlingServlet;
import org.apache.sling.api.servlets.*;
import org.apache.sling.api.*;
import javax.servlet.*;

import org.sakaiproject.nakamura.api.lite.Repository;
import org.sakaiproject.nakamura.api.lite.content.ContentManager;


import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;

import org.sakaiproject.nakamura.api.solr.SolrServerService;

@Component(immediate=true, metatype=true)
@SlingServlet(methods = "POST", paths = "/system/solr-remedy", generateComponent=false)
  public class SolrRemedialServlet extends SlingAllMethodsServlet
  {
    public static final Logger LOGGER = LoggerFactory.getLogger(SolrRemedialServlet.class);

    private LinkedBlockingQueue<String> queue;
    private ContentManager contentManager;
    private Thread indexingThread;

    @Reference
    Repository repository;

    @Reference
    SolrServerService solrServer;


    @Activate
    protected void activate(Map<?, ?> props)
    {
      LOGGER.info("Activating Solr Remedial Servlet");

      try {
        contentManager = repository.loginAdministrative().getContentManager();
      } catch (org.sakaiproject.nakamura.api.lite.ClientPoolException e) {
        LOGGER.error("Failed to get content manager: {}", e);
        e.printStackTrace();
      } catch (org.sakaiproject.nakamura.api.lite.StorageClientException e) {
        LOGGER.error("Failed to get content manager: {}", e);
        e.printStackTrace();
      } catch (org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException e) {
        LOGGER.error("Failed to get content manager: {}", e);
        e.printStackTrace();
      }

      queue = new LinkedBlockingQueue<String>();

      startReindexingThread();
    }


    private void startReindexingThread()
    {
      indexingThread = new Thread() {
          public void run() {
            try {
              while (true) {
                String path = queue.take();

                LOGGER.info("Sending reindex event for path '{}'", path);

                try {
                  contentManager.triggerRefresh(path);
                } catch (org.sakaiproject.nakamura.api.lite.StorageClientException e) {
                  LOGGER.error("Couldn't reindex path '{}': {}", path, e);
                  e.printStackTrace();
                } catch (org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException e) {
                  LOGGER.error("Couldn't reindex path '{}': {}", path, e);
                  e.printStackTrace();
                }

                Thread.sleep(1000);
              }
            } catch (InterruptedException e) {
              LOGGER.info("Reindexing thread is shutting down.");
            }
          }
        };

      indexingThread.setName("Solr Remedial Indexing Thread");
      indexingThread.start();
    }


    private void triggerSolrCommit() throws ServletException, IOException
    {
      LOGGER.info("Firing a Solr commit.");
      try {
        solrServer.getUpdateServer().commit(false, false);
      } catch (org.apache.solr.client.solrj.SolrServerException e) {
        throw new ServletException(e);
      }
    }


    @Override
    protected void doPost(SlingHttpServletRequest request,
                          SlingHttpServletResponse response)
      throws ServletException, IOException
    {
      if (!"admin".equals(request.getRemoteUser())) {
        LOGGER.error("You need to be admin to run this.");
        return;
      }

      String operation = request.getParameter("operation");

      if ("reindex".equals(operation)) {
        String path = request.getParameter("path");

        if (path != null) {
          LOGGER.info("Queueing path '{}' for reindexing.", path);
          queue.offer(path);
        } else {
          LOGGER.error("You didn't specify a path...");
        }          
      } else if ("commit".equals(operation)) {
        triggerSolrCommit();
      } else {
        LOGGER.error("No idea what you're talking about...");
      }
    }


    @Deactivate
    protected void deactivate(Map<?, ?> props)
    {
      LOGGER.info("Deactivating Solr Remedial Servlet");

      indexingThread.interrupt();
    }
  }
