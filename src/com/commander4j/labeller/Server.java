package com.commander4j.labeller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.Logger;

import com.commander4j.labeller.web.WebServer;
import com.commander4j.xml.JXMLDocument;

public class Server extends Thread
{

	Logger logger = org.apache.logging.log4j.LogManager.getLogger((Server.class));
	LabellerUtility utils = new LabellerUtility();
	public boolean started = false;
	public boolean shutdown = false;
	ConcurrentHashMap<String, Labeller> labellers = new ConcurrentHashMap<String, Labeller>();
	public static String Site = "Test";

	/** Web viewer config, read from config.xml. Port 0 means "not configured" = not started. */
	private String webHost = "0.0.0.0";
	private int webPort = 0;
	private WebServer webServer = null;

	private void requestStop()
	{
		logger.info("Server - requestStop");
		stopWebServer();
		stopAllLabellers();
	}

	public void run()
	{
		setName("Server");
		logger.info("Server - Run");
		startupInterface();
		started = true;
		while ((shutdown==false) && (started==true))
		{
			utils.pause(100);
		}

	}

	public int stop(int exitCode)
	{
		logger.info("Server - stop");
		try
		{
			requestStop();
			utils.pause(100);
			started = false;

		} catch (Exception ex)
		{
		}

		return exitCode;
	}

	public void addLabeller(LabellerProperties prop, String script,HashMap<String,LabellerDBLink>  dblinks)
	{
		logger.debug("Server - addLabeller [" + prop.getId() + "]");
		Labeller labeller = new Labeller(prop, script, dblinks);
		labeller.setName(prop.getId());
		labellers.put(prop.getId(), labeller);
	}

	public void startLabeller(String id)
	{
		logger.debug("Server - startLabeller [" + id + "]");
		labellers.get(id).start();
		while (labellers.get(id).isAlive() == false)
		{
			logger.debug("Server - waiting for Labeller  [" + id + "] thread to start.");
			utils.pause(10);
		}
		//logger.info("Server - Labeller  [" + id + "] thread started.");
	}

	public void startAllLabellers()
	{
		logger.info("Server - startAllLabellers");
		Iterator<Entry<String, Labeller>> it = labellers.entrySet().iterator();
		while (it.hasNext())
		{
			HashMap.Entry<String, Labeller> pair = (HashMap.Entry<String, Labeller>) it.next();
			startLabeller(pair.getKey().toString());
		}
	}

	public void stopLabeller(String id)
	{
		logger.debug("Server - stopLabeller - [" + id + "]");
		if (labellers.get(id).isAlive())
		{
			labellers.get(id).shutdown();

			while (labellers.get(id).isAlive())
			{
				utils.pause(10);
			}
		}
	}

	public void stopAllLabellers()
	{
		logger.debug("Server - stopAllLabellers");
		Iterator<Entry<String, Labeller>> it = labellers.entrySet().iterator();
		while (it.hasNext())
		{
			HashMap.Entry<String, Labeller> pair = (HashMap.Entry<String, Labeller>) it.next();
			stopLabeller(pair.getKey().toString());

			it.remove();
		}
	}

	public void deleteLabeller(String id)
	{
		if (labellers.get(id).isAlive() == false)
		{
			labellers.remove(id);
		}
	}

	/**
	 * Snapshot of the loaded labellers, for the read-only web status endpoint. A copy, so a
	 * caller iterating it is unaffected by a concurrent start/stop. Sorted by id so the web
	 * viewer always lists them in the same order - the underlying map is a hash map, so its
	 * own iteration order is arbitrary and can reshuffle when a labeller is added or removed.
	 */
	public List<Labeller> getLabellers()
	{
		List<String> ids = new ArrayList<String>(labellers.keySet());
		Collections.sort(ids, String.CASE_INSENSITIVE_ORDER);

		List<Labeller> result = new ArrayList<Labeller>();

		for (String id : ids)
		{
			Labeller labeller = labellers.get(id);

			if (labeller != null)
			{
				result.add(labeller);
			}
		}

		return result;
	}

	public void startupInterface()
	{
		logger.info("Server - startupInterface");
		loadSite();
		loadLabellers();
		startAllLabellers();
		startWebServer();
	}

	/**
	 * Starts the read-only web viewer if config.xml asked for it. Diagnostics must never
	 * take the line down, so any failure here (a port already in use being the likely one)
	 * is logged and swallowed — the labellers carry on regardless.
	 */
	private void startWebServer()
	{
		if (webPort == 0)
		{
			// loadWebServerConfig has already logged why.
			return;
		}

		try
		{
			webServer = new WebServer(this, webHost, webPort);
			webServer.start();
		}
		catch (Exception ex)
		{
			webServer = null;
			logger.error("Server - unable to start web viewer on " + webHost + ":" + webPort + " - " + ex.getMessage());
			logger.warn("Server - continuing without the web viewer; labelling is unaffected.");
		}
	}

	private void stopWebServer()
	{
		if (webServer != null)
		{
			try
			{
				webServer.stop();
			}
			catch (Exception ex)
			{
				logger.error("Server - error stopping web viewer : " + ex.getMessage());
			}
			webServer = null;
		}
	}

	public void requestPrint(String id)
	{
		if (labellers.containsKey(id))
		{
			if (labellers.get(id).isAlive())
			{
				logger.info("Server - requestPrint - ["+id+"]");
				labellers.get(id).requestPrint();
			}
			else
			{
				logger.warn("Server - requestPrint - Labeller Thread not running for ["+id+"]");
			}
		}
		else
		{
			logger.warn("Server - requestPrint - Labeller Thread not loaded for ["+id+"]");
		}
	}

	public void loadSite()
	{
		logger.info("Server - loadSite");
		JXMLDocument xmltest = new JXMLDocument("./xml/config/config.xml");
		Site = xmltest.findXPath("//config/site[1]/@id");
		loadWebServerConfig(xmltest);
	}

	/**
	 * Reads the optional web viewer settings from config.xml:
	 *
	 * <pre>
	 *     &lt;webserver port="8080"/&gt;
	 *     &lt;webserver port="8080" host="127.0.0.1" enabled="Y"/&gt;
	 * </pre>
	 *
	 * The element is optional and absent from every site's config.xml today, so no listener
	 * opens until someone adds it — an upgrade on its own changes nothing. {@code host}
	 * defaults to all interfaces and {@code enabled} to Y, so a port is all that is needed.
	 */
	private void loadWebServerConfig(JXMLDocument xmltest)
	{
		String port = xmltest.findXPath("//config/webserver[1]/@port").trim();
		String host = xmltest.findXPath("//config/webserver[1]/@host").trim();
		String enabled = xmltest.findXPath("//config/webserver[1]/@enabled").trim();

		if (port.equals(""))
		{
			logger.info("Server - no <webserver> element in config.xml - web viewer not started.");
			return;
		}

		if (enabled.equals("") == false && enabled.toUpperCase().equals("Y") == false)
		{
			logger.info("Server - web viewer disabled in config.xml (enabled=" + enabled + ")");
			return;
		}

		try
		{
			int portNumber = Integer.parseInt(port);
			if ((portNumber < 1) || (portNumber > 65535))
			{
				logger.error("Server - web viewer port out of range (1..65535) in config.xml : [" + port + "] - viewer not started.");
				return;
			}
			webPort = portNumber;
		}
		catch (NumberFormatException ex)
		{
			logger.error("Server - web viewer port is not a number in config.xml : [" + port + "] - viewer not started.");
			return;
		}

		if (host.equals("") == false)
		{
			webHost = host;
		}

		logger.info("Server - web viewer configured on " + webHost + ":" + webPort);
	}
	
	public void loadLabellers()
	{
		logger.info("Server - loadLabellers");
		
		HashMap<String,LabellerDBLink>  dblinks = loadDatabaseLinks();

		JXMLDocument xmltest = new JXMLDocument("./xml/config/"+Site+"/labellers.xml");

		String site = xmltest.findXPath("//labellers/site[1]/@id");
		int labeller = 1;

		while (xmltest.findXPath("//labellers/site/labeller[" + String.valueOf(labeller) + "]/@id").trim().equals("") == false)
		{
			String id = xmltest.findXPath("//labellers/site/labeller[" + String.valueOf(labeller) + "]/@id").trim();
			String enabled = xmltest.findXPath("//labellers/site/labeller[" + String.valueOf(labeller) + "]/enabled").trim();
			String ipAddress = xmltest.findXPath("//labellers/site/labeller[" + String.valueOf(labeller) + "]/ipAddress").trim();
			String portNumber = xmltest.findXPath("//labellers/site/labeller[" + String.valueOf(labeller) + "]/portNumber").trim();
			String commandFile = xmltest.findXPath("//labellers/site/labeller[" + String.valueOf(labeller) + "]/commandFile").trim();
			String inputPath = xmltest.findXPath("//labellers/site/labeller[" + String.valueOf(labeller) + "]/inputPath").trim();
			String inputFile = xmltest.findXPath("//labellers/site/labeller[" + String.valueOf(labeller) + "]/inputFile").trim();
			logger.debug("Read config for ["+id+"]");

			if (enabled.toUpperCase().equals("Y"))
			{

				LabellerProperties prop1 = new LabellerProperties();

				prop1.setSite(site);
				prop1.setId(id);
				prop1.setIpAddress(ipAddress);
				prop1.setPortNumber(Integer.valueOf(portNumber));
				prop1.setInputPath(inputPath);
				prop1.setInputFile(inputFile);
				addLabeller(prop1, commandFile,dblinks);
			}

			labeller++;
		}

	}
	
	public HashMap<String,LabellerDBLink> loadDatabaseLinks()
	{
		HashMap<String,LabellerDBLink> result = new HashMap<String,LabellerDBLink>();
		
		logger.info("Server - loadDatabaseLinks");

		JXMLDocument xmltest = new JXMLDocument("./xml/config/"+Site+"/databases.xml");

		int dbSeq = 1;

		while (xmltest.findXPath("//databases/database[" + String.valueOf(dbSeq) + "]/@id").trim().equals("") == false)
		{
			String id = xmltest.findXPath("//databases/database[" + String.valueOf(dbSeq) + "]/@id").trim();

			logger.debug("Reading config for Database Link : ["+id+"]");
			
			String jdbcDriver = xmltest.findXPath("//databases/database[" + String.valueOf(dbSeq) + "]/jdbcDriver").trim();
			String jdbcUsername = xmltest.findXPath("//databases/database[" + String.valueOf(dbSeq) + "]/jdbcUsername").trim();
			String jdbcPassword = xmltest.findXPath("//databases/database[" + String.valueOf(dbSeq) + "]/jdbcPassword").trim();
			String jdbcServer = xmltest.findXPath("//databases/database[" + String.valueOf(dbSeq) + "]/jdbcServer").trim();
			String jdbcPort = xmltest.findXPath("//databases/database[" + String.valueOf(dbSeq) + "]/jdbcPort").trim();
			String jdbcSID = xmltest.findXPath("//databases/database[" + String.valueOf(dbSeq) + "]/jdbcSID").trim();
			String jdbcDatabase = xmltest.findXPath("//databases/database[" + String.valueOf(dbSeq) + "]/jdbcDatabase").trim();
			
			LabellerDBLink dbLink = new LabellerDBLink();
			
			dbLink.setJdbcDriver(jdbcDriver);
			dbLink.setJdbcUsername(jdbcUsername);
			dbLink.setJdbcPassword(jdbcPassword);
			dbLink.setJdbcServer(jdbcServer);
			dbLink.setJdbcPort(jdbcPort);
			dbLink.setJdbcSID(jdbcSID);
			dbLink.setJdbcDatabase(jdbcDatabase);

			int sqlSeq = 1;
			
			while (xmltest.findXPath("//databases/database/sqlSelect[" + String.valueOf(sqlSeq) + "]/@id").trim().equals("") == false)
			{
				String idsql = xmltest.findXPath("//databases/database/sqlSelect[" + String.valueOf(sqlSeq) + "]/@id").trim();
				String statement = xmltest.findXPath("//databases/database/sqlSelect[" + String.valueOf(sqlSeq) + "]/statement").trim();
				
				dbLink.addSQL(idsql, statement);

				logger.debug("Reading config for Database Link : ["+id+"] : SQL statement id ["+idsql+"]");
				
				sqlSeq++;
			}
			
			result.put(id, dbLink);

			dbSeq++;
		}
		
		return result;
	}

	public static void main(String[] args)
	{
		Logger logger = org.apache.logging.log4j.LogManager.getLogger((Server.class));

		logger.info("Server starting");
		System.out.println("");
		LabellerUtility utils = new LabellerUtility();
		Server server = new Server();


		server.start();

		while (server.isAlive() == false)
		{
			utils.pause(10);
		}
		
		while (server.isAlive())
		{
			utils.pause(10);
		}

		server.requestStop();

		System.out.println("");
		logger.info("Server finished.");
		System.exit(0);
	}

}
