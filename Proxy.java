/* Sample skeleton for proxy */

import java.io.*;
import java.lang.*;
import java.util.*;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.rmi.registry.*;
import java.rmi.Naming;

class Proxy {

	private static String cacheDir;
	private static Long cacheCapacity;
	private static FSInf srv;
	private static int proxyNumber;

	private static class FileHandler implements FileHandling {

		//Initializing all Hashmaps needed 
		private static HashMap<Integer, RandomAccessFile> fdStore = new HashMap<Integer, RandomAccessFile>(3000);
		private static HashMap<Integer, String> nameStore = new HashMap<Integer, String>(3000);
		private static HashMap<Integer, String> dirStore = new HashMap<Integer, String>(3000);
		private static HashMap<Integer, Integer> usedStore = new HashMap<Integer, Integer>(3000);
		private static HashMap<String, Integer> closeStore = new HashMap<String, Integer>(3000);
		private static HashMap<String, Integer> openStore =new HashMap<String, Integer>(3000);
		private static HashMap<String, Integer> peopleStore = new HashMap<String, Integer>(3000);
    	private static LinkedList<String> lruList = new LinkedList();

    	//All the read-write locks for shared hashmaps and LRU linked list
		private static ReentrantReadWriteLock closeStoreLock = new ReentrantReadWriteLock(true);
		private static ReentrantReadWriteLock openStoreLock = new ReentrantReadWriteLock(true);
		private static ReentrantReadWriteLock peopleStoreLock = new ReentrantReadWriteLock(true);
    	private static ReentrantReadWriteLock linkedListLock = new ReentrantReadWriteLock(true);

    	//Constants
		private static int chunkSize = 16384;
		private static Integer fdVal = new Integer(5);

		//Amount of space remaning in the cache
   	 	private static Integer remainingSpace = new Integer((int)(long)cacheCapacity);


		/**
 		 * Combine the given version with filename into acceptable string format
 		 * 
 		 * @param filename, veraion
 		 * @return String that is concatenated
 		*/
		public String makeVersion(String filename, int version) {
			return filename + "." + Integer.toString(version);
		}


		/**
 		 * Normalize path makes a stack to remove any ../ or ./ from given path
 		 * 
 		 * @param path - the path to normalize
 		 * @return String consisting the normalized path (relative canonical path)
 		*/
	    public String normalizePath(String path) {
			Stack<String> pathStack = new Stack<String>();
			String[] allDirs = path.split("/");
			String finalPath = "";

			//Fill up stack
			for(int i = 0; i <  allDirs.length; i ++) {
				if(allDirs[i].equals("..")) {
					if(pathStack.empty()) return "";
					pathStack.pop();
				}
				else if(!allDirs[i].equals(".")) {
					pathStack.push(allDirs[i]);
				}
			}

			//Make path string from stack
			while(!pathStack.empty()) {
				if(finalPath.equals("")) finalPath = pathStack.pop();
				else finalPath = pathStack.pop() + "/" + finalPath;

			}

	      	return finalPath;
	    }


		/**
 		 * Strip version takes a string and removes the version number from it
 		 * 
 		 * @param String filename
 		 * @return String withoiut version number
 		*/	    
		public String stripVersion(String filename) {
			int index = filename.lastIndexOf(".");
			return filename.substring(0, index);
		}


		/**
 		 * My version takes a filename and returns a string that indicates version number of file
 		 * 
 		 * @param String filename
 		 * @return Whether the foo function call is successful. Raise IllegalArgumentException for error case.
 		*/
		public String myVersion(String filename) {
			int index = filename.lastIndexOf(".");
			return filename.substring(index+1, filename.length());
		}


		/**
 		 * Copy file copies a file from source to destination using chunkSize
 		 * 
 		 * @param source and dest files
 		 * @return Returns nothing but raises en exception if file streams fail
 		*/
		private static void copyFile(File source, File dest) throws IOException {
			InputStream input = null;
			OutputStream output = null;

			try {
				input = new FileInputStream(source);
				output = new FileOutputStream(dest);
				byte[] buf = new byte[chunkSize];
				int bytesRead;
				while ((bytesRead = input.read(buf)) > 0) {
					output.write(buf, 0, bytesRead);
				}
			} finally {
				input.close();
				output.close();
			}
		}

		/**
 		 * Evict deletes files from the cache that can be deleted in LRU order
 		 * 
 		 * @param Int amountToRemove that indicates how much of the cache needs to be freed
 		 * @return 1 in case of success -1 in case of failure
 		*/
	    public int evict(int amountToRemove) {
			while(amountToRemove > 0) {
			if(lruList.size() == 0) return -1;
			String fileToRemove = lruList.remove();
			try {
				int updatedNumber = -999;
				if(closeStore.containsKey(fileToRemove)) {
					updatedNumber  = closeStore.get(fileToRemove);
				} else return -1;
				String fullToRemovePath = cacheDir + "/" + makeVersion(fileToRemove, updatedNumber);
				File f = new File(fullToRemovePath);
				int fileLength = (int)f.length();
				amountToRemove -= fileLength;
				updateRemainingSpace(fileLength, true);
				f.delete();
			} catch (Exception e) {
			  return -1;
			}
			}
			return 1;
	    }


	    /**
 		 * Check evict and update checks if evicts are necessary
 		 * 
 		 * @param length of file that needs to be put into the cache
 		 * @return 1 on success, -1 on error
 		*/
	    public synchronized int checkEvictAndUpdate(int lengthOfFile) {
	        if(lengthOfFile > remainingSpace) return evict(lengthOfFile - remainingSpace);
	        return 1;
	    }


	    /**
 		 * Updates remaining space
 		 * 
 		 * @param amount that indicates how much to add/subtract, boolean add indicatres addition/subtraction
 		 * @return void.
 		*/
	    public synchronized void updateRemainingSpace(int amount, boolean add) {
			if(add) remainingSpace += amount;
			else remainingSpace -= amount;
	    }

	    /**
 		 * Clean up removes all the older version of a file not in use
 		 * 
 		 * @param filename, which version to remove from, whether to remove current version or not
 		 * @return void.
 		*/
		public void cleanUp(String filename, int fromVersion, boolean deleteCurrent) {
			peopleStoreLock.writeLock().lock();
			String completeFilename;
			File toDeleteFile;

			//Check if current file needs to be deleted
			if(deleteCurrent) {
				completeFilename = makeVersion(filename, fromVersion);

				//Delete current even if it has one user as its not most updated version
				if(peopleStore.containsKey(completeFilename) && peopleStore.get(completeFilename) == 1) {
					toDeleteFile = new File(completeFilename);
          			updateRemainingSpace((int)toDeleteFile.length(), true);
					toDeleteFile.delete();
					peopleStore.remove(completeFilename);
				}
        		fromVersion--;
			}

			//Loop from current version all the way back
			while(fromVersion >= 0) {
				completeFilename = makeVersion(filename, fromVersion);
				if(peopleStore.containsKey(completeFilename) && peopleStore.get(completeFilename) == 0) {
					toDeleteFile = new File(completeFilename);
          			updateRemainingSpace((int)toDeleteFile.length(), true);
					toDeleteFile.delete();
					peopleStore.remove(completeFilename);
				}
				fromVersion --;
			}

			peopleStoreLock.writeLock().unlock();
		}

		/**
 		 * Get file from server fetches the file from server if needed
 		 * 
 		 * @param Path of file to fetch, version of file to copy into, length of file
 		 * @return 1 in case of success -1 in case of failure
 		*/
		public int getFileFromServer(String path, int version, int lengthOfFile) {
			try {
				String toCreatePath = makeVersion(path, version);
			  	RandomAccessFile raf = new RandomAccessFile(cacheDir + "/" + toCreatePath, "rw");
				long offset = 0;
				byte[] bytesRead;

				//Check if eviction is needed before copying the file
		        if(checkEvictAndUpdate(lengthOfFile) == -1) return Errors.EBUSY;

		        updateRemainingSpace(lengthOfFile, false);

		        //Where entire file fits within chunksize
		        if(chunkSize >= lengthOfFile) {
					bytesRead = srv.readFile(path, (int)lengthOfFile, 0, 1);
					raf.write(bytesRead);
					raf.close();
					return 1;
				}

				//Use offset and grab chunksize bytes
				while(offset+chunkSize < lengthOfFile) {
					bytesRead = srv.readFile(path, chunkSize, offset, 0);
					raf.write(bytesRead);
					offset += chunkSize;
				}

				bytesRead = srv.readFile(path, (int)(lengthOfFile-offset), offset, 1);
				raf.write(bytesRead);
				raf.close();
				return 1;

			} catch (Exception r) {
				return -1;
			}

		}

		/**
 		 * Put the updates file back into server
 		 * 
 		 * @param Path of file to put, version number of file
 		 * @return 1 in case of success -1 in case of failure
 		*/
		public int updateFileInServer(String path, int version) {
			try {
				String toCreatePath = makeVersion(path, version);
			  	RandomAccessFile raf = new RandomAccessFile(cacheDir + "/" + toCreatePath, "rw");
        		srv.fileInfo(path, 1);
				long lengthOfFile = (int)raf.length();
				long offset = 0;
				byte[] bytesToWrite;

				//Where entire file fits within chunksize
				if(chunkSize >=lengthOfFile) {
          			bytesToWrite = new byte[(int)lengthOfFile];
					raf.read(bytesToWrite);
					srv.writeFile(path, bytesToWrite, 0, 1, proxyNumber);
					raf.close();
					return 1;
				}

				//Use offset and grab chunksize bytes
				while(offset+chunkSize < lengthOfFile) {
          			bytesToWrite = new byte[chunkSize];
					raf.read(bytesToWrite);
					srv.writeFile(path, bytesToWrite, offset, 0, proxyNumber);
					offset += chunkSize;
				}

        		bytesToWrite = new byte[(int)(lengthOfFile-offset)];
				raf.read(bytesToWrite);
				srv.writeFile(path, bytesToWrite, offset, 1, proxyNumber);
				raf.close();
				return 1;

			} catch (Exception r) {
				return -1;
			}

		}


		/**
 		 * Make parent makes all the parent directories within the cache for a path
 		 * 
 		 * @param Path 
 		 * @return true on success, false on failure
 		*/
	    public boolean makeParent(String path) {
			File parenDir = new File(path);
			try {
				return parenDir.getParentFile().mkdirs();
			} catch(SecurityException e)  {
				return false;
			}
	    }


	    /**
 		 * Opens a file
 		 * 
 		 * @param Path to open file and option to open file with
 		 * @return Returns one of the many errors of open on failure and fd on success
 		*/
		public int open(String path, OpenOption o) {
			path = normalizePath(path);
			if(path == "") return Errors.EINVAL;

			//If first time file has to opened
			closeStoreLock.writeLock().lock();
			openStoreLock.writeLock().lock();
			if(!closeStore.containsKey(path)) {
				openStore.put(path, 1);
				closeStore.put(path, 0);
			}
			openStoreLock.writeLock().unlock();
			closeStoreLock.writeLock().unlock();


			//Get the close and open numbers of files
			closeStoreLock.readLock().lock();
			int closeNumber = closeStore.get(path);
			closeStoreLock.readLock().unlock();

			openStoreLock.writeLock().lock();
			int openNumber = openStore.get(path);
			openStore.put(path, openNumber+1);
			openStoreLock.writeLock().unlock();

		  	String pathTemp = path;
			String pathToOpen = cacheDir + "/" + makeVersion(path, openNumber);
			String existsPath = cacheDir + "/" + makeVersion(path, closeNumber);
			String halfPathToOpen = makeVersion(path, openNumber);
			String halfPathToClose = makeVersion(path, closeNumber);
			File f;
			boolean existsOnCache;
			int[] existsValid;
			int existsOnServer;
			int isInvalid;
			int lengthOfFile;

			//Check if path is null or not
			try {
				f = new File(existsPath);
				existsOnCache = f.exists();
			} catch(NullPointerException npe) {
				return Errors.EINVAL;
			}
		      

			//Get whether file exists, is valid and what the length is from server
			try {
				existsValid = srv.fileExistsAndValid(pathTemp, proxyNumber, existsOnCache, false);
				existsOnServer = existsValid[0];
				isInvalid = existsValid[1];
				lengthOfFile = existsValid[2];
			} catch (RemoteException r) {
				return Errors.EBUSY;
			}

      		if(existsOnServer == 2) {
				if(o == OpenOption.READ) {
					dirStore.put(fdVal, pathTemp);
					fdVal++;
					return fdVal - 1;
				} else {
					return Errors.EISDIR;
				}
			}


			//Remove latest copy our file from the linkedList
			linkedListLock.writeLock().lock();
			lruList.remove(pathTemp);
			linkedListLock.writeLock().unlock();

      		//If create new is called and file exists either in cache or server return error
			if(o == OpenOption.CREATE_NEW && (existsOnCache || (existsOnServer != 0))) {
				return Errors.EEXIST;
			}


			//If option is create and file doesnt exist in cache but exists on server, get the file
			//If file does exists in cache, continue as usual
			//If file doesn't exist in cache or server continue as usual
			if(o == OpenOption.CREATE) {
        		makeParent(pathToOpen);
				if(!existsOnCache && (existsOnServer != 0)) {
					if(getFileFromServer(pathTemp, openNumber, lengthOfFile) == -1) return Errors.EBUSY;
				}
			}

			//If option is read/write and file doesnt exist in cache or seerver return error
			//If file exists in server but not cache copy file into cache
			//Else continue as usual
			if((o == OpenOption.READ || o == OpenOption.WRITE)) {
				if(!existsOnCache && !(existsOnServer != 0)) {
					return Errors.ENOENT;
				} else if(!existsOnCache && (existsOnServer != 0)) {
          			makeParent(pathToOpen);
					if(getFileFromServer(pathTemp, openNumber, lengthOfFile) == -1) return Errors.EBUSY;
				}
			}

      		makeParent(pathToOpen);
      		//Check if copy is invalid, if it is then grab it from server
			if(isInvalid == 1 && existsOnCache) {
				if(getFileFromServer(pathTemp, openNumber, lengthOfFile) == -1) return Errors.EBUSY;
			}



			String optionsForFile;
			//Decide what the option to set is
			switch (o) {
				case READ:  optionsForFile = "r";
       						usedStore.put(fdVal, 0);
						    break;
				case WRITE: optionsForFile = "rw";
                    		usedStore.put(fdVal, 0);
							break;
				case CREATE: optionsForFile = "rw";
				  			 break;
				case CREATE_NEW: optionsForFile  = "rw";
								 usedStore.put(fdVal, 2);
								 break;
				default: return Errors.EINVAL;
			}

			//Store that this file will/won't be modified
			if(o == OpenOption.CREATE && (existsOnServer == 0)) usedStore.put(fdVal, 2);
      		else usedStore.put(fdVal,1);


			//See if making file is possible, if yes put it in the hashmap
			//else return appropriate error.
			try {
				String toMakePath;
				String toPutPath;

				//If no need to create to new copy then open the latest version
				if(o == OpenOption.READ && isInvalid != 1 && existsOnCache) {
					toMakePath = existsPath;
					toPutPath = halfPathToClose;
					RandomAccessFile raf = new RandomAccessFile(existsPath, optionsForFile);
				} else {

					//Create a copy if writing, creating new or create and doesnt exist on cache
					if((o == OpenOption.WRITE || o == OpenOption.CREATE) && existsOnCache && isInvalid != 1) {
						File destFile = new File(pathToOpen);
						File sourceFile = new File(existsPath);
            			int tempLen = (int)sourceFile.length();
            			if(checkEvictAndUpdate(tempLen) == -1) return Errors.EBUSY;
            			updateRemainingSpace(tempLen,false);
						copyFile(sourceFile, destFile);
					}
					  toMakePath = pathToOpen;
					  toPutPath = halfPathToOpen;

				}

				//Open the file, put it in all hashmaps with appropriate values
				RandomAccessFile raf = new RandomAccessFile(toMakePath, optionsForFile);
				fdStore.put(fdVal, raf);
				nameStore.put(fdVal, toPutPath);
				peopleStoreLock.writeLock().lock();
				if(peopleStore.containsKey(toMakePath)) {
					peopleStore.put(toMakePath, (peopleStore.get(toMakePath))+1);
				} else {
					peopleStore.put(toMakePath, 1);
				}
				peopleStoreLock.writeLock().unlock();
				fdVal++;
				return fdVal-1;
			} catch (FileNotFoundException fne) {
				return Errors.ENOENT;
			} catch (SecurityException se) {
				return Errors.EPERM;
			} catch (Exception e) {
				return Errors.ENOENT;
			}
		}


		/**
 		 * Closes a file
 		 * 
 		 * @param Fd of file to close
 		 * @return Returns one of the many errors of close on failure and 0 on success
 		*/
		public int close(int fd) {
			//If fd is non existent in either hashmap return error
			boolean inDirStore = dirStore.containsKey(fd);
			if(!fdStore.containsKey(fd) && !inDirStore) {
				return Errors.EBADF;
			}

			//Remove it from directory hashmap
			if(inDirStore) {
				dirStore.remove(fd);
				return 0;
			}

			//Get it from fd hashmap
			RandomAccessFile toCloseFile = fdStore.get(fd);

			//Try closing it, if doesn't work return IO error
			try {
				String myPath = nameStore.get(fd);
				String strippedPath = stripVersion(myPath);
				String totalPath = cacheDir + "/" + myPath;
        		int ownVersion = Integer.parseInt(myVersion(myPath));

        		//In case file wasn't modified
				if((usedStore.get(fd) == 0)) {
					closeStoreLock.writeLock().lock();

					//Check if read is latest version
					if(ownVersion >= closeStore.get(strippedPath)) {
						cleanUp(cacheDir + "/" + strippedPath, ownVersion,false);
			            linkedListLock.writeLock().lock();
			            lruList.addLast(strippedPath);
			            linkedListLock.writeLock().unlock();
						closeStore.put(strippedPath, ownVersion);
					} else {
            			cleanUp(cacheDir + "/" + strippedPath, ownVersion,true);
         			}
					closeStoreLock.writeLock().unlock();
				}

				//File was modified
				if((usedStore.get(fd) != 0)) {
					openStoreLock.readLock().lock();
					int currentOpenVersion = openStore.get(strippedPath);
					openStoreLock.readLock().unlock();

					cleanUp(cacheDir + "/" + strippedPath, currentOpenVersion-1,false);

					closeStoreLock.writeLock().lock();
					closeStore.put(strippedPath, ownVersion);
					closeStoreLock.writeLock().unlock();

					linkedListLock.writeLock().lock();
					lruList.addLast(strippedPath);
					linkedListLock.writeLock().unlock();

					if(updateFileInServer(strippedPath, ownVersion) == -1) return Errors.EBUSY;
				}

				//Decrement number of users for a file
				peopleStoreLock.writeLock().lock();
        		if(peopleStore.containsKey(totalPath)) peopleStore.put(totalPath, (peopleStore.get(totalPath))-1);
				peopleStoreLock.writeLock().unlock();

				toCloseFile.close();
        		fdStore.remove(fd);
				nameStore.remove(fd);
				usedStore.remove(fd);
				return 0;
			} catch (IOException ie) {
				return -5; //Predefined C Error code NOT A MAGIC NUMBER
			}
		}


		/**
 		 * Write to a file
 		 * 
 		 * @param Fd of file to write to, buffer containing content to be written
 		 * @return Returns one of the many errors of write on failure and amount written on success
 		*/
		public long write( int fd, byte[] buf ) {
			//If it is directory return error.
			if(dirStore.containsKey(fd)) {
				return Errors.EINVAL;
			}

			//If fd is non-existent within hashmap return error
			if(!fdStore.containsKey(fd)) {
				return Errors.EBADF;
			}

			//Get it from hashmap, see if it can be written to
			RandomAccessFile toWriteFile = fdStore.get(fd);

			try {
				int toWriteFileLength = (int)toWriteFile.length();
				int bufLength = (int)buf.length;
				int fp = (int)toWriteFile.getFilePointer();
				int delta = (fp + bufLength) - (toWriteFileLength);

				//Check if the extra amount being written is > than remaining space
        		if(delta > remainingSpace) {
          			if(checkEvictAndUpdate(delta) == -1) return Errors.EBUSY;
        		}
        		updateRemainingSpace(delta, false);
				toWriteFile.write(buf);
        		usedStore.put(fd, 2);
				return buf.length;
			} catch (IOException ie) {
				return Errors.EBADF;
			}
		}

		/**
 		 * Read a file
 		 * 
 		 * @param Fd of file to read, buffer to be read into
 		 * @return Returns one of the many errors of write on failure and amount read on success
 		*/
		public long read( int fd, byte[] buf ) {
			//If it is directory return error.
			if(dirStore.containsKey(fd)) {
				return Errors.EISDIR;
			}

			//If fd is non-existent within hashmap return error
			if(!fdStore.containsKey(fd)) {
				return Errors.EBADF;
			}

			//Get it from hashmap, see if it can be read from
			RandomAccessFile toReadFile = fdStore.get(fd);

			try {
				int readRet = toReadFile.read(buf);
        		if(readRet < 0) return 0;
        		if(usedStore.containsKey(fd) && usedStore.get(fd) != 2) usedStore.put(fd, 0);
				return readRet;
			} catch (EOFException ee) {
        		return 0;
      		} catch (IOException ie) {
				return Errors.EBADF;
			} catch (NullPointerException npe) {
				return -14; //Predefined C Error code NOT A MAGIC NUMBER
      		}
		}


		/**
 		 * Lseek (move file pointer) to a place in the file
 		 * 
 		 * @param Fd of file to lseek, how much to move file pointer, where to start
 		 * @return Returns one of the many errors of lseek on failure and file pointer position on success
 		*/
		public long lseek( int fd, long pos, LseekOption o ) {

			//If it is directory return error.
			if(dirStore.containsKey(fd)) {
				return Errors.EINVAL;
			}

			//If fd is non-existent within hashmap return error
			if(!fdStore.containsKey(fd)) {
				return Errors.EBADF;
			}

			//Get it from hashmap, see if it can be lseeked
			RandomAccessFile toLseekFile = fdStore.get(fd);
			try {
				long currPtr  = toLseekFile.getFilePointer();
				long finalPos;

				//Decide where final position is suppoed to be
				switch(o) {
					case FROM_START: finalPos = pos;
                           break;
					case FROM_CURRENT: finalPos = pos+ currPtr;
                             break;
					case FROM_END: finalPos = toLseekFile.length() + pos;
                         break;
					default: finalPos = -1;
				}
				//If final position < 0, invalid error, else call seek.
				if(finalPos >= 0) {
					toLseekFile.seek(finalPos);
					return finalPos;
				} else {
					return Errors.EINVAL;
				}

			} catch (IOException ie) {
				return Errors.EBADF;
			}
		}


		/**
 		 * unlink/delete a file
 		 * 
 		 * @param path of file to delete
 		 * @return Returns one of the many errors of unlink on failure and return 0 on success
 		*/
		public int unlink( String path ) {
		  	String newPath = normalizePath(path);
		  	if(newPath == "") return Errors.EINVAL;
		  	else path = newPath;

			File f;
		  	String pathTemp = path;
		  	path = cacheDir + "/" + path;

			//Get file with the path, delete it
			//If deletion not possible then Busy error
			try {
				int existsOnServer;
		    	int currOpenNumber;

		    	//Check if it exists on server
		    	try {
		      		existsOnServer = (srv.fileExistsAndValid(pathTemp,proxyNumber, false, true))[0];
		   	 	} catch (RemoteException r) {
		      		return Errors.EBUSY;
		    	}

				if(existsOnServer ==  2){
					return Errors.EISDIR;
				}
		    	File toDelete;

		    	//Make file of path to delete
		    	try {
		      		toDelete = new File(path);
		    	} catch (NullPointerException e) {
		      		return Errors.ENOENT;
		    	}

		    	//See if file exists
		    	if(!toDelete.exists() && (existsOnServer != 0)) {
					try {
						return srv.unlink(pathTemp);
					} catch (RemoteException re) {
						return Errors.EBUSY;
					}
		    	}


		    	//Check if it is a directory
		    	if (toDelete.isDirectory()) {
		    		return Errors.EISDIR;
		    	} else {

		    		//Cases based on whether file exists on server and on local cache
		      		if(!toDelete.delete()) return Errors.ENOENT;
		      		else {
		        		try {
		          			if(existsOnServer != 0) {
		            			int retVal = srv.unlink(pathTemp);
		            			if(retVal != 0) return retVal;
		          			} else {
		            			return Errors.ENOENT;
		          			}
		        		} catch (RemoteException re) {
		          			return Errors.EBUSY;
		        		}
		        		return 0;
		      		}
		    	}

			} catch (NullPointerException npe) { //Incase path was null
				return Errors.ENOENT;
			} catch (SecurityException se) {
				return Errors.EPERM; //Permission erorr for inaccesible files
			}
		}

		public void clientdone() {
			return;
		}

	}

	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}

	public static void main(String[] args) throws IOException {
		if(args.length != 4) {
			return;
		}

		String ip = args[0];
		int port = Integer.parseInt(args[1]);
		cacheDir = args[2];
		cacheCapacity = Long.parseLong(args[3]);
		proxyNumber = Integer.parseInt(System.getenv("proxyport15440"));

		try {
			srv = (FSInf) Naming.lookup("//" + ip + ":" + port + "/Server");
		} catch(Exception nbe) {
			System.err.println("Could not connect to server\n");
			return;
		}

		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

