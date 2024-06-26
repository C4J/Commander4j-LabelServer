package com.commander4j.labeller;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.commander4j.util.JUtility;
import com.commander4j.zpl.Code128Switcher;

public class LabellerCMDFile
{

	Logger logger = org.apache.logging.log4j.LogManager.getLogger((LabellerCMDFile.class));
	public HashMap<String, String> variables = new HashMap<String, String>();
	public HashMap<String, String> fileData = new HashMap<String, String>();
	public ArrayList<String> labelIndex = new ArrayList<String>();
	public LinkedList<LabellerCMDLine> commandLines = new LinkedList<LabellerCMDLine>();
	private String Delimiter = "";

	public HashMap<String, String> getVariables()
	{
		return variables;
	}

	public void addLine(LabellerCMDLine line)
	{
		commandLines.addLast(line);

		labelIndex.add(line.label);
	}

	public int getLinefromLabel(String label)
	{
		int result = -1;
		result = labelIndex.indexOf(label);

		return result;
	}

	public void setDelimiter(String del)
	{
		Delimiter = del;
	}

	public String removeDelimitors(String var)
	{
		String result = var;

		if (Delimiter.equals("") == false)
		{
			try
			{
				String[] valuesInQuotes = StringUtils.substringsBetween(var, Delimiter, Delimiter);
				result = "";
				for (int x = 0; x < valuesInQuotes.length; x++)
				{
					result = result + valuesInQuotes[x];

					if (x < valuesInQuotes.length - 1)
					{
						result = result + ",";
					}
				}
			}
			catch (Exception ex)
			{
				result = var;
			}
		}

		return result;
	}

	public String getValueAtLine(int line)
	{

		String result = commandLines.get(line).val;

		if (commandLines.get(line).command.equals("VARIABLE") == false)
		{
			if (Delimiter.equals("") == false)
			{
				result = removeDelimitors(result);
			}

			result = replaceVariables(result);
		}

		result = replaceFileData(result);

		result = execFunctions(result);

		result = result.replace("±", ",");

		return result;
	}

	public String execFunctions(String VAL)
	{
		String result = VAL;
		Logger logger = org.apache.logging.log4j.LogManager.getLogger((LabellerCMDFile.class));

		boolean functionFound = true;

		while (functionFound)
		{

			functionFound = false;
			String func = "SUBSTRING";

			while (result.contains(func))
			{
				functionFound = true;
				
				int functionNameStartPos = result.indexOf(func); 
				String beforeFunction = result.substring(0, functionNameStartPos); 
				int functionNameEndPos = functionNameStartPos + func.length();
				String stringAfterFunctionName = result.substring(functionNameEndPos); 
				int closingBracketPosition=getClosingBracketPosition(stringAfterFunctionName);
				String allParameters = stringAfterFunctionName.substring(1, closingBracketPosition);

				String afterFunction = stringAfterFunctionName.substring(closingBracketPosition + 1);

				String[] parameterArray = allParameters.split(",");

				String inputString = parameterArray[0];

				String inputStartPos = parameterArray[1];

				int startIndex = Integer.parseInt(inputStartPos);

				String inputEndPos = parameterArray[2];

				int endIndex = Integer.parseInt(inputEndPos);

				try
				{

					String substr = "";

					// substr = inputString.substring(startIndex, endIndex);
					substr = inputString.substring(startIndex, startIndex + endIndex - 1);

					result = beforeFunction + substr + afterFunction;

					logger.debug("SUBSTRING: " + result);

				}
				catch (Exception ex)
				{
					result = "error " + ex.getMessage();
				}

			}

			func = "EXTRACT_DATE";

			while (result.contains(func))
			{
				
				functionFound = true;
				
				int functionNameStartPos = result.indexOf(func); 
				String beforeFunction = result.substring(0, functionNameStartPos); 
				int functionNameEndPos = functionNameStartPos + func.length();
				String stringAfterFunctionName = result.substring(functionNameEndPos);
				int closingBracketPosition=getClosingBracketPosition(stringAfterFunctionName);
				String allParameters = stringAfterFunctionName.substring(1, closingBracketPosition);

				String afterFunction = stringAfterFunctionName.substring(closingBracketPosition + 1);

				String[] parameterArray = allParameters.split(",");

				String inputDateString = parameterArray[0];

				DateFormat inputDateFormat = new SimpleDateFormat(parameterArray[1], Locale.ENGLISH);

				try
				{
					Date inputDate;

					inputDate = inputDateFormat.parse(inputDateString);

					DateFormat df = new SimpleDateFormat(parameterArray[2]);

					String outputDate = df.format(inputDate);

					result = beforeFunction + outputDate + afterFunction;

					logger.debug("EXTRACT_DATE: " + result);

				}
				catch (Exception ex)
				{
					result = "error " + ex.getMessage();
				}

			}

			func = "TIMESTAMP";

			while (result.contains(func))
			{
				
				functionFound = true;
				
				int functionNameStartPos = result.indexOf(func);
				String beforeFunction = result.substring(0, functionNameStartPos);
				int functionNameEndPos = functionNameStartPos + func.length();
				String stringAfterFunctionName = result.substring(functionNameEndPos);
				int closingBracketPosition = stringAfterFunctionName.indexOf(")");
				String allParameters = stringAfterFunctionName.substring(1, closingBracketPosition);

				String afterFunction = stringAfterFunctionName.substring(closingBracketPosition + 1);

				String[] parameterArray = allParameters.split(",");

				
				DateFormat dateFormat = new SimpleDateFormat(parameterArray[0], Locale.ENGLISH);
				Date date = new Date();
				String outputDate = dateFormat.format(date);

				result = beforeFunction + outputDate + afterFunction;

			}
			
			func = "REPLACE";

			while (result.contains(func))
			{
				
				functionFound = true;
				
				int functionNameStartPos = result.indexOf(func);
				String beforeFunction = result.substring(0, functionNameStartPos);
				int functionNameEndPos = functionNameStartPos + func.length();
				String stringAfterFunctionName = result.substring(functionNameEndPos); 
				int closingBracketPosition=getClosingBracketPosition(stringAfterFunctionName);
				String allParameters = stringAfterFunctionName.substring(1, closingBracketPosition);

				String afterFunction = stringAfterFunctionName.substring(closingBracketPosition + 1);

				String[] parameterArray = allParameters.split(",");

				String inputString = "";
				String findString = "";
				String replaceString = "";

				try
				{
					inputString = parameterArray[0];
				}
				catch (Exception ex)
				{
					inputString = "";
				}

				try
				{
					findString = parameterArray[1];
				}
				catch (Exception ex)
				{
					findString = "";
				}

				try
				{
					replaceString = parameterArray[2];
				}
				catch (Exception ex)
				{
					replaceString = "";
				}

				try
				{
					String outputString = inputString.replaceAll(findString, replaceString);

					result = beforeFunction + outputString + afterFunction;

					logger.debug("REPLACE: " + result);

				}
				catch (Exception ex)
				{
					result = "error " + ex.getMessage();
				}

			}

			func = "PADLEFT";

			while (result.contains(func))
			{
				functionFound = true;
				
				int functionNameStartPos = result.indexOf(func); 
				String beforeFunction = result.substring(0, functionNameStartPos);
				int functionNameEndPos = functionNameStartPos + func.length();
				String stringAfterFunctionName = result.substring(functionNameEndPos); 
				int closingBracketPosition=getClosingBracketPosition(stringAfterFunctionName);
				String allParameters = stringAfterFunctionName.substring(1, closingBracketPosition);

				String afterFunction = stringAfterFunctionName.substring(closingBracketPosition + 1);

				String[] parameterArray = allParameters.split(",");

				String inputString = "";
				String padSize = "";
				String padChar = "";

				try
				{
					inputString = parameterArray[0];
				}
				catch (Exception ex)
				{
					inputString = "";
				}

				try
				{
					padSize = parameterArray[1];
				}
				catch (Exception ex)
				{
					padSize = String.valueOf(inputString.length()).trim();
				}

				try
				{
					padChar = parameterArray[2];
				}
				catch (Exception ex)
				{
					padChar = "";
				}

				try
				{
					int padS = Integer.valueOf(padSize);

					while (inputString.length() < padS)
					{
						inputString = padChar + inputString;
					}

					if (inputString.length() > padS)
					{
						inputString = inputString.substring(inputString.length() - padS);
					}

					result = beforeFunction + inputString + afterFunction;

					logger.debug("PADLEFT : " + result);

				}
				catch (Exception ex)
				{
					result = "error " + ex.getMessage();
				}

			}
			
			func = "CODE128SWITCHER";

			while (result.contains(func))
			{
				
				functionFound = true;
				
				int functionNameStartPos = result.indexOf(func);
				String beforeFunction = result.substring(0, functionNameStartPos);
				int functionNameEndPos = functionNameStartPos + func.length();
				String stringAfterFunctionName = result.substring(functionNameEndPos);
				int closingBracketPosition=getClosingBracketPosition(stringAfterFunctionName);
				String allParameters = stringAfterFunctionName.substring(1, closingBracketPosition);
				String afterFunction = stringAfterFunctionName.substring(closingBracketPosition + 1);

				String[] parameterArray = allParameters.split(",");

				String rawdata = parameterArray[0];
				
				Code128Switcher zplmodeswitcher = new Code128Switcher();
				
				String zpldata  = zplmodeswitcher.process(rawdata);

				result = beforeFunction + zpldata + afterFunction;

			}

		}
		
		return result;
	}
	
	public int getClosingBracketPosition(String stringAfterFunctionName)
	{
		String lookat="";
		int level = 0;
		int closingBracketPosition=0;
		for (int temp=0;temp<(stringAfterFunctionName.length());temp++)
		{
			lookat = stringAfterFunctionName.substring(temp, temp+1);
			if (lookat.equals("("))
			{
				level++;
			}
			if (lookat.equals(")"))
			{
				level--;
				if (level==0)
				{
					closingBracketPosition=temp;
					break;
				}
			}
		}
		return closingBracketPosition;
	}
	

	public String replaceVariables(String var)
	{
		String result = var;

		Set<Entry<String, String>> set = variables.entrySet();
		Iterator<Entry<String, String>> iterator = set.iterator();
		while (iterator.hasNext())
		{
			Map.Entry<String, String> mentry = (Map.Entry<String, String>) iterator.next();
			result = StringUtils.replaceOnce(result, mentry.getKey().toString(), mentry.getValue().toString().replace(",", "±"));
		}

		if (var.equals(result) == false)
		{
			logger.debug("[" + var + "] changed to [" + result + "]");
		}

		return result;
	}

	public String replaceFileData(String var)
	{
		String result = var;

		Set<Entry<String, String>> set = fileData.entrySet();
		Iterator<Entry<String, String>> iterator = set.iterator();
		while (iterator.hasNext())
		{
			Map.Entry<String, String> mentry = (Map.Entry<String, String>) iterator.next();
			boolean again = true;
			String beforeReplace = "";
			while (again)
			{
				beforeReplace = result;
				result = StringUtils.replaceOnce(result, mentry.getKey().toString(), mentry.getValue().toString().toString().replace(",", "±"));
				if (result.equals(beforeReplace))
				{
					again = false;
				}
				else
				{
					again = true;
				}
			}

		}

		return result;
	}

	public Boolean loadFile(LabellerProperties prop, String filename)
	{
		Boolean result = true;

		variables.clear();
		labelIndex.clear();
		commandLines.clear();
		Delimiter = "";

		filename = System.getProperty("user.dir") + java.io.File.separator + "labeller_cmd" + java.io.File.separator + prop.getSite() + java.io.File.separator + filename;
		System.out.println("");
		logger.debug("loadFile [" + filename + "]");
		System.out.println("");
		try
		{
			List<String> strings = Files.readAllLines(FileSystems.getDefault().getPath(filename), Charset.forName("UTF-8"));
			for (int x = 0; x < strings.size(); x++)
			{
				String sourceLine = strings.get(x);
				if (sourceLine.trim().equals("") == false)
				{
					if (sourceLine.startsWith("/*") == false)
					{
						sourceLine = sourceLine + JUtility.padSpace(50);
						LabellerCMDLine cmdLine = new LabellerCMDLine();
						cmdLine.label = sourceLine.substring(0, 18).trim();
						cmdLine.command = sourceLine.substring(18, 48).trim();
						cmdLine.val = sourceLine.substring(48).trim();

						addLine(cmdLine);

						// logger.debug("Line > " + cmdLine);
					}
				}
			}
		}
		catch (IOException e)
		{
			e.printStackTrace();
			result = false;
		}
		System.out.println("");
		getLinefromLabel("Start");

		return result;
	}

}
