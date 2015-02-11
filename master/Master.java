/************************************************************
Class : Master.java
This is the main class on the Master side.
It creates a serverSocket and waits for a connection from clients.
On receiving a new connection, it spawns a new response thread
to handle the connection from a given slave.
*************************************************************/

import java.net.*;
import java.io.*;
import java.lang.Integer;

public class Master
{
	public static int masterRcvPort;
	public static int slaveSendPort;
	public static int backlog = Integer.MAX_VALUE;

	private static void ParseConfigFile(String configFileName) throws IOException, FileNotFoundException
	{
		BufferedReader configFile = new BufferedReader(new FileReader(configFileName));

		//read and initialize values from Master.config file
		String inputLine;
		inputLine = configFile.readLine();
		while (null != inputLine)
		{
			String[] parseInputLine = inputLine.split("=");
			if (parseInputLine[0].equals("MasterRcvPort"))
			{
				masterRcvPort = Integer.parseInt(parseInputLine[1]);
				System.out.println("Master Rcv Port = " + masterRcvPort);
			}
			else if (parseInputLine[0].equals("SlaveSendPort"))
			{
				slaveSendPort = Integer.parseInt(parseInputLine[1]);
				System.out.println("Slave Send Port = " + slaveSendPort);
			}
			inputLine = configFile.readLine();
		}
	}

	public static void main(String[] args) throws IOException, FileNotFoundException
	{
		ServerSocket serverSocket = null;
		boolean listening = true;
		System.out.println(InetAddress.getLocalHost());
                String configFileName;
                if (args.length > 0 && null != args[0])
                        configFileName = args[0];
                else
                        configFileName = "Master.config";

		ParseConfigFile(configFileName);
		try
		{
			serverSocket = new ServerSocket(masterRcvPort, backlog);

		}
		catch(IOException e)
		{
			System.err.println("Master: Could not listen on port: " + masterRcvPort);
			System.exit(-1);
		}

		while (listening)
		{
			System.out.println("Master: Waiting for a connection...");
			new MasterRespondThread(serverSocket.accept(), slaveSendPort).start();
		}
		serverSocket.close();
	}
}
