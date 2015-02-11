/********************************************************
* This class represents the adjustable clock in the slave
* used to synchronize and send the DoS attacks.
* The logical clock consists of the Slave's hardware clock
* and an adjustment functin
* LC = HC + A(t) = HC + ((m*HC) + N)
* For our purposes, m=0 since we ignore amortization
*		    N=(ET+D)-aftrT.
* We synchronize with the master often enough to ignore
* drift and amortization. As a result the logical clock
* is a discontinuous function but we update the synchronization
* and N often enough.
*********************************************************/
import java.io.*;
import java.net.*;
import java.lang.*;
import java.util.*;

public class LogicalClock
{
	public static Long m = new Long(0);
	public static Long N = new Long(0);
	public static int totalDoSPacketsSent = 0;

	// Return the latest logical clock time (latest HW clock + N)
	public synchronized Long getCurrentTime()
	{
		Long HWClock = (new Date()).getTime();
		return ((m + 1) * HWClock) + N; 
	}

	//Update the adjustment variable N.
	public synchronized void setN(Long newValue)
	{
		N = newValue;
	}
}
