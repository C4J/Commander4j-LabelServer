package com.commander4j.labeller;

import org.apache.logging.log4j.Logger;

public class LabellerProperties
{
	Logger logger = org.apache.logging.log4j.LogManager.getLogger((LabellerProperties.class));
	private String site = "site1";
	private String id = "Printer1";
	private String ipAddress = "127.0.0.1";
	private int portNumber = 8000;	
	private int connectTimeout = 5000;
	private String inputPath = "./";
	private String inputFile = "*.*";	

	public void setInputPath(String path)
	{
		inputPath = path;
	}
	
	public void setInputFile(String file)
	{
		inputFile = file;
	}
	
	public String getInputPath()
	{
		return inputPath;
	}
	
	public String getInputFile()
	{
		return inputFile;
	}
	
	public int getConnectTimeout()
	{
		return connectTimeout;
	}
	public void setConnectTimeout(int connectTimeout)
	{
		this.connectTimeout = connectTimeout;
	}

	public synchronized String getId()
	{
		return id;
	}
	public void setId(String id)
	{
		this.id = id;
	}

	public String getSite()
	{
		return site;
	}
	public void setSite(String site)
	{
		this.site = site;
	}
	
	public String getIpAddress()
	{
		return ipAddress;
	}
	public void setIpAddress(String ipAddress)
	{
		this.ipAddress = ipAddress;
	}
	public int getPortNumber()
	{
		return portNumber;
	}
	public void setPortNumber(int portNumber)
	{
		this.portNumber = portNumber;
	}
	
	public Boolean loadProperties(String printerID)
	{
		Boolean result = true;
		
		return result;
	}
	
	public String toString()
	{
		return "id=["+id+ "] ip=["+ipAddress+ "] port=["+String.valueOf(portNumber)+"]";
	}

}
