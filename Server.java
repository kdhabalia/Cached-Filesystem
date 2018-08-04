
import java.io.*;
import java.lang.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.*;
import java.rmi.Naming;




public class Server extends UnicastRemoteObject implements FSInf {

	private String root;
	private HashMap<String, ReentrantReadWriteLock> lockStore = new HashMap<String, ReentrantReadWriteLock>(5000);
	private HashMap<String, Integer> invalidStore = new HashMap<String, Integer>(5000);

	/**
	 * CONSTRUCTOR
	*/
	public Server(String rootDir) throws RemoteException {
		root = rootDir + "/";
	}


	/**
	 * Check is the file exists, its valid and if both then also give length of file
	 * 
	 * @param filename, which proxy the request is from, whether it exists on the cache, whether call was from unlink
	 * @return Array consisting of [whetherItExists, whetherItsValid, lengthOfFile]
	*/
	public int[] fileExistsAndValid(String filename, int proxyNumber, boolean existsOnCache, boolean unlink)
    	throws RemoteException {
			File f = new File(root, filename);
			String totalFileName = root + filename;
		    int existsNumber;
		    int validNumber;
		    int length = -1;

			if(f.exists()) {
				if(f.isDirectory()) existsNumber = 2;
				existsNumber = 1;
			} else existsNumber = 0;

	    if(!invalidStore.containsKey(filename)) validNumber = 0;
	    else if(invalidStore.get(filename) != proxyNumber) validNumber = 1;
	    else validNumber = 0;

	    if(existsNumber == 1) length = (int)fileInfo(filename, 0);

	    if(existsOnCache && validNumber == 0 && existsNumber == 1 && !unlink) unlockRead(filename);
	    if(unlink) unlockRead(filename);
	    return (new int[] {existsNumber, validNumber, length});
	}


	/**
	 * Fileinfo gives the gives the length of the file
	 * 
	 * @param filename and whether its called with read/write 
	 * @return Length of file
	*/
	public long fileInfo(String filename, int functionality) throws  RemoteException {
		if(!lockStore.containsKey(filename)) {
			ReentrantReadWriteLock newLock = new ReentrantReadWriteLock(true);
			lockStore.put(filename, newLock);
		}
		try {
			if(functionality == 1) lockStore.get(filename).writeLock().lock();
			else lockStore.get(filename).readLock().lock();

      		RandomAccessFile raf = new RandomAccessFile(root + filename, "rw");
		  	long length = raf.length();
		  	raf.close();
		  	return length;
    	} catch (Exception e) {
      		return -1;
    	}
	}


	/**
	 * Unlock the read lock on file  - local use only
	 * 
	 * @param filename to unlock lock for
	 * @return void
	*/
	public void unlockRead(String filename) throws RemoteException {
    	try {
      	lockStore.get(filename).readLock().unlock();
   		} catch (Exception e) {
      		return;
    	}
  	}


	/**
	 * Read the file from the server
	 * 
	 * @param filename, amount to read, where to read from, to unlock/lock/do nothing
	 * @return byte array consisting of read file
	*/
	public byte[] readFile(String filename, int amountToRead, long offset, int stage) throws RemoteException {
		try {
      		RandomAccessFile raf = new RandomAccessFile(root + filename, "rw");
			  byte[] fileData = new byte[amountToRead];
			  raf.seek(offset);
			  raf.readFully(fileData);
			  raf.close();
			  if(stage == 1) {
				  lockStore.get(filename).readLock().unlock();
			  }
		  	return fileData;
    	} catch (Exception e) {
      		return new byte[0];
    	}
	}

	/**
	 * Write file to server
	 * 
	 * @param filename, buf to write to, offset to write from, lock/unlock/nothing to do, which proxy
	 * @return void.
	*/
	public void writeFile(String filename, byte[] toWriteBuf, long offset, int stage, int proxyNumber)
			throws RemoteException {
    	try{
			RandomAccessFile raf = new RandomAccessFile(root + filename, "rw");
      		raf.seek(offset);
		  	raf.write(toWriteBuf);
		  	raf.close();
      		if(stage == 1) {
        	  invalidStore.put(filename, proxyNumber);
				lockStore.get(filename).writeLock().unlock();
			}
    	} catch (Exception e) {
      		return;
    	}
	}

	/**
	 * Delete/unlink file from server
	 * 
	 * @param path of file to delete
	 * @return Errors of unlink or 0 on success
	*/
	public int unlink( String path ) throws RemoteException{
		File f;
		//Get file with the path, delete it
		//If deletion not possible then Busy error
		try {
			f =  new File(path);
			if (f.delete()) {
				return 0;
			} else {
				return -2; //Not valid error (ERROR CONSTANT NOT MAGIC NUMBER)
			}
		} catch (NullPointerException npe) { //Incase path was null
			return -2; //Not valid error (ERROR CONSTANT NOT MAGIC NUMBER)
		} catch (SecurityException se) {
			return -1; //Permission erorr for inaccesible files
		}
	}


	public static void main(String[] args) throws IOException {
		if(args.length != 2) {
			System.err.println("SERVER: Invalid server arguments given");
			return;
		}
		Integer portNumber = Integer.parseInt(args[0]);
		String rootDir = args[1];


		try {
			LocateRegistry.createRegistry(portNumber);
			Server srv = new Server(rootDir);
			String name = "//127.0.0.1:" + portNumber + "/Server";
			Naming.rebind(name, srv);
		} catch (RemoteException e) {
			System.err.println("Port could not be created");
			return;
		}


	}
}

