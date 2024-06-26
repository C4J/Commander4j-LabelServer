package com.commander4j.zpl;

import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;

public class Code128Switcher
{
	
	Logger logger = org.apache.logging.log4j.LogManager.getLogger((Code128Switcher.class));
	
	private Pattern pattern = Pattern.compile("\\d*");
	private int lookAhead = 3;

	// private String StartA = ">9>8"; // Numeric Pairs give Alpha/Numerics
	private String StartB = ">:>8"; // Normal Alpha/Numeric - DEFAULT
	private String StartC = ">;>8"; // All numeric (00 - 99)

	// private String SwitchA = ">7"; // Numeric Pairs give Alpha/Numerics
	private String SwitchB = ">6"; // Normal Alpha/Numeric - DEFAULT
	private String SwitchC = ">5"; // All numeric (00 - 99)

	public String process(String input)
	{
		return parseZPL(input);
	}

	private String parseZPL(String input)
	{
		String result = "";
		String current = "";
		String next = "";
		String lookaheadString = "";
		String prefixCode = "";
		
		int position = 0;
		String mode = "";

		if (input == null)
		{
			input = "";
		}
		
		String formatted = input;
		input= input.replace("(", "");
		input= input.replace(")", "");
		
		int seqnumbercounter = 0;
		boolean evenNumberCount = false;

		logger.info("0.........1.........2.........3.........4.........5");
		logger.info("012345678901234567890123456789012345678901234567890");
		logger.info(input);
		logger.info("");
		logger.info("Pos    Chr/Next   Ahead     Numeric  NumberSeq  NumberPair");
		logger.info("==========================================================");

		while (position < input.length())
		{

			prefixCode = "";

			do
			{
				current = getCharacter(input, position);
				position++;

				if (isFNC1token(current))
				{
					logger.info(padString(String.valueOf(position - 1), true, 2, " ") + "       <--==FNC1==-->");
					result = result + getFNC1();
				}
				else
				{
					if (isNumeric(current))
					{

						seqnumbercounter++;
						
						if (seqnumbercounter % 2 == 0)
						{
							evenNumberCount = true;
						}
						else
						{
							evenNumberCount = false;
						}

					}
					else
					{
						seqnumbercounter = 0;
						
						evenNumberCount = false;
					}

				}

			}
			while (isFNC1token(current) && (position < input.length()));

			lookaheadString = getLookahead(input, position, lookAhead);

			next = getLookahead(input, position, 1);

			// ************ Code A,B,C Logic goes here ****************//

			switch (mode)
			{
			case "":

				// BARCODE INITIAL MODE //

				String scan = "";

				if (input.length() >= 4)
				{
					// Check first 4 characters
					scan = current + lookaheadString;
				}
				else
				{
					if (input.length() >= 2)
					{
						// Check first 2 characters
						scan = current + next;
					}
					else
					{
						// Check first character
						scan = current;
					}
				}

				if (isNumeric(scan))
				{
					if (input.length() >= 2)
					{
						logger.info("Start with Numeric");
						mode = "numeric";
						prefixCode = StartC;
					}
					else
					{
						logger.info("Start with Alpha Numeric");
						mode = "alphanum";
						prefixCode = StartB;
					}
				}
				else
				{
					logger.info("Start with Alpha Numeric");
					mode = "alphanum";
					prefixCode = StartB;
				}

				break;

			case "numeric":

				// CHECK IF MODE CHANGES FROM NUMERIC //

				// **** more logic here *****

				if (next.equals("") && (evenNumberCount==false))
				{
					logger.info("Switch to Alpha Numeric");
					mode = "alphanum";
					prefixCode = SwitchB;
				}
				else
				{
					if (isNumeric(current) == false)
					{
						logger.info("Switch to Alpha Numeric");
						mode = "alphanum";
						prefixCode = SwitchB;
					}
				}

				break;

			case "alphanum":

				// CHECK IF MODE CHANGES FROM ALPHANUM //

				// **** more logic here *****

				if (isNumeric(current + lookaheadString))
				{
					logger.info("Switch to Numeric");
					mode = "numeric";
					prefixCode = SwitchC;
				}

				break;

			default:
				logger.info("Unrecognised mode");
			}

			logger.info(padString(String.valueOf(position - 1), true, 2, " ") + "   -   " + padString(current, true, 1, " ") + "-" + padString(next, true, 1, " ") + "   -   " + padString(lookaheadString, true, lookAhead, " ") + "   -   "
					+ padString(String.valueOf(isNumeric(lookaheadString)), true, 5, " ") + "   -   " + padString(String.valueOf(seqnumbercounter), true, 3, " ") + "  -  " + padString(String.valueOf(evenNumberCount), true, 5, " "));

			result = result + prefixCode + current;
		}

		logger.info("");
		logger.info("Results");
		logger.info("-------");
		logger.info("");
		logger.info("Formatted String  " + formatted);
		logger.info("Input String      " + input);
		logger.info("Output String     " + result);
		logger.info("");

		return result;
	}

	private String getCharacter(String input, int position)
	{
		String result = "";

		if (position <= input.length())
		{
			result = input.substring(position, position + 1);
		}

		return result;
	}

	private String getLookahead(String input, int position, int lookahead)
	{
		String result = "";
		int LookAheadPosition = position;
		int count = 0;
		String LookAheadCharacter = "";

		while ((LookAheadPosition < input.length()) && (count < lookahead))
		{
			LookAheadCharacter = getCharacter(input, LookAheadPosition);

			if (LookAheadCharacter.equals(getFNC1token()) == false)
			{
				result = result + getCharacter(input, LookAheadPosition);
			}
			else
			{
				break;
			}

			count++;
			LookAheadPosition++;
		}

		return result;
	}

	private String getFNC1token()
	{
		String result = "^";

		return result;
	}

	private boolean isFNC1token(String input)
	{
		return input.equals(getFNC1token());
	}

	private String getFNC1()
	{
		String result = ">8";

		return result;
	}

	private boolean isNumeric(String strNum)
	{

		if (strNum == null)
		{
			return false;
		}
		if (strNum == "")
		{
			return false;
		}
		return pattern.matcher(strNum).matches();
	}

	private String padString(String input, boolean right, int size, String character)
	{
		int inputlength = 0;
		String result = replaceNullStringwithBlank(input);

		inputlength = result.length();

		if (inputlength > size)
		{
			result = result.substring(0, size);
		}
		else
		{
			if (inputlength < size)
			{
				if (right == true)
				{
					result = result + padString(size - inputlength, character);
				}
				else
				{
					result = padString(size - inputlength, character) + result;
				}
			}
		}

		return result;
	}

	private String padString(int size, String character)
	{
		String s = "";

		for (int i = 0; i < size; i++)
		{
			s = s + character;
		}

		return s;
	}

	private String replaceNullStringwithBlank(String value)
	{
		if (value == null)
		{
			value = "";
		}

		return value;
	}

	public static void main(String[] args)
	{
		
		String param = "";

		if (args.length > 0)
		{
			param = args[0];
		}
		else
		{
			param = "(01)03222277182662(20)10(15)230531(10)10209718C0^(99)010203";
		}
		

		Code128Switcher cs = new Code128Switcher();

		// (01)03222277182662(20)10152301001010209718C0^(99)010203

		// ^ = FNC1

		cs.process(param);

	}

}
