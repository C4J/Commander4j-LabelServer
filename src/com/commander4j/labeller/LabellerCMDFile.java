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
import org.apache.commons.lang3.Strings;
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

		// Functions are resolved innermost-first: on each pass we locate the function
		// call with the highest start index (which is provably the innermost - no other
		// call can begin after it yet still contain it), evaluate it, and splice the
		// value back. This lets a child's result feed its parent, e.g.
		// REPLACE(SUBSTRING(...),a,b) or CODE128SWITCHER(PADLEFT(...)...).
		String[] functionNames = new String[]
		{ "SUBSTRING", "EXTRACT_DATE", "TIMESTAMP", "REPLACE", "PADLEFT", "CODE128SWITCHER" };

		int safety = 1000; // termination guard against a value that re-introduces a call

		while (safety-- > 0)
		{
			// Find the innermost call = the registered "NAME(" with the largest start index.
			int callStart = -1;
			String callName = "";
			for (String name : functionNames)
			{
				int pos = result.lastIndexOf(name + "(");
				if (pos > callStart)
				{
					callStart = pos;
					callName = name;
				}
			}

			if (callStart < 0)
			{
				break; // no function calls remain
			}

			String beforeFunction = result.substring(0, callStart);
			String stringAfterFunctionName = result.substring(callStart + callName.length());
			int closingBracketPosition = getClosingBracketPosition(stringAfterFunctionName);

			if (closingBracketPosition <= 0)
			{
				logger.warn("Unterminated function call [" + callName + "(] in [" + result + "] - leaving literal");
				break;
			}

			String allParameters = stringAfterFunctionName.substring(1, closingBracketPosition);
			String afterFunction = stringAfterFunctionName.substring(closingBracketPosition + 1);
			String[] parameterArray = allParameters.split(",");

			String value = evaluateFunction(callName, parameterArray);

			// Escape commas in the result so a parent function's split(",") treats this
			// whole value as a single argument. getValueAtLine() restores them via
			// result.replace("±", ",") after execFunctions returns.
			value = value.replace(",", "±");

			result = beforeFunction + value + afterFunction;
		}

		return result;
	}

	private String evaluateFunction(String functionName, String[] parameterArray)
	{
		String result = "";
		Logger logger = org.apache.logging.log4j.LogManager.getLogger((LabellerCMDFile.class));

		switch (functionName)
		{
		case "SUBSTRING":
			// SUBSTRING(string, start, length) - returns 'length' characters starting
			// at position 'start', where 'start' is 1-based (the first character is 1,
			// not 0 as in Java). Bounds are clamped so data shorter than requested
			// returns what is available rather than throwing an error onto the label.
			try
			{
				String inputString = parameterArray[0];
				int startIndex = Integer.parseInt(parameterArray[1]) - 1; // 1-based -> 0-based
				int length = Integer.parseInt(parameterArray[2]);

				if (startIndex < 0)
				{
					logger.warn("SUBSTRING start position [" + parameterArray[1] + "] is less than 1 - treating as 1 (first character)");
					startIndex = 0;
				}
				if (startIndex > inputString.length())
				{
					startIndex = inputString.length();
				}

				int endIndex = startIndex + length;
				if (endIndex > inputString.length())
				{
					endIndex = inputString.length();
				}
				if (endIndex < startIndex)
				{
					endIndex = startIndex;
				}

				result = inputString.substring(startIndex, endIndex);
				logger.debug("SUBSTRING: " + result);
			}
			catch (Exception ex)
			{
				result = "error " + ex.getMessage();
			}
			break;

		case "EXTRACT_DATE":
			try
			{
				String inputDateString = parameterArray[0];
				DateFormat inputDateFormat = new SimpleDateFormat(parameterArray[1], Locale.ENGLISH);
				Date inputDate = inputDateFormat.parse(inputDateString);
				DateFormat df = new SimpleDateFormat(parameterArray[2]);
				result = df.format(inputDate);
				logger.debug("EXTRACT_DATE: " + result);
			}
			catch (Exception ex)
			{
				result = "error " + ex.getMessage();
			}
			break;

		case "TIMESTAMP":
			try
			{
				DateFormat dateFormat = new SimpleDateFormat(parameterArray[0], Locale.ENGLISH);
				Date date = new Date();
				result = dateFormat.format(date);
			}
			catch (Exception ex)
			{
				result = "error " + ex.getMessage();
			}
			break;

		case "REPLACE":
			String inputStringR = "";
			String findString = "";
			String replaceString = "";

			try
			{
				inputStringR = parameterArray[0];
			}
			catch (Exception ex)
			{
				inputStringR = "";
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
				result = inputStringR.replaceAll(findString, replaceString);
				logger.debug("REPLACE: " + result);
			}
			catch (Exception ex)
			{
				result = "error " + ex.getMessage();
			}
			break;

		case "PADLEFT":
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

				result = inputString;
				logger.debug("PADLEFT : " + result);
			}
			catch (Exception ex)
			{
				result = "error " + ex.getMessage();
			}
			break;

		case "CODE128SWITCHER":
			try
			{
				String rawdata = parameterArray[0];
				Code128Switcher zplmodeswitcher = new Code128Switcher();
				result = zplmodeswitcher.process(rawdata);
			}
			catch (Exception ex)
			{
				result = "error " + ex.getMessage();
			}
			break;

		default:
			result = "";
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
			result = Strings.CS.replaceOnce(result, mentry.getKey().toString(), mentry.getValue().toString().replace(",", "±"));
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
				result =  Strings.CS.replaceOnce(result, mentry.getKey().toString(), mentry.getValue().toString().replace(",", "±"));
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
			System.out.println("Reading file ["+filename +"] "+e.getMessage());
			result = false;
		}
		System.out.println("");
		getLinefromLabel("Start");

		return result;
	}

}
