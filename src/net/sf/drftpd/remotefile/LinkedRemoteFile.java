package net.sf.drftpd.remotefile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.rmi.ConnectException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.StringTokenizer;
import java.util.Vector;

import net.sf.drftpd.FatalException;
import net.sf.drftpd.NoAvailableSlaveException;
import net.sf.drftpd.ObjectExistsException;
import net.sf.drftpd.PermissionDeniedException;
import net.sf.drftpd.SFVFile;
import net.sf.drftpd.master.RemoteSlave;
import net.sf.drftpd.master.SlaveManagerImpl;
import net.sf.drftpd.master.config.FtpConfig;
import net.sf.drftpd.slave.Slave;
import net.sf.drftpd.slave.Transfer;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/**
 * Represents the file attributes of a remote file.
 * 
 * @author Morgan Christiansson <mog@linux.nu>
 */

public class LinkedRemoteFile implements RemoteFileInterface, Serializable {
	private static final String EMPTY_STRING = "".intern();
	private static Logger logger =
		Logger.getLogger(LinkedRemoteFile.class.getName());
	static final long serialVersionUID = 3585958839961835107L;
	private long _checkSum;

	private Map _files;
	private transient FtpConfig _ftpConfig;
	private String _group;
	//private Random rand = new Random();

	//private String path;
	private boolean _isDeleted = false;
	private long _lastModified;

	private long _length;
	private String _name;
	private String _owner;

	private LinkedRemoteFile _parent;
	/////////////////////// SLAVES
	protected List _slaves;
	private long _xfertime = 0;

	protected SFVFile sfvFile;
	/**
	 * Creates an empty RemoteFile directory, usually used as an empty root directory that
	 * <link>{merge()}</link> can be called on.
	 * 
	 * Used if no file database exists to start a tree from scratch.
	 */
	public LinkedRemoteFile(FtpConfig ftpConfig) {
		_ftpConfig = ftpConfig;

		_lastModified = System.currentTimeMillis();
		_length = 0;
		_parent = null;
		_name = EMPTY_STRING;
		_files = Collections.synchronizedMap(new Hashtable());
		_slaves = Collections.synchronizedList(new ArrayList(1));
	}
	/**
	 * Creates a RemoteFile from file or creates a directory tree representation.
	 * 
	 * Used by DirectoryRemoteFile.
	 * Called by other constructor, ConnectionManager is null if called from SlaveImpl.
	 * 
	 * They all end up here.
	 * @param parent the parent of this file
	 * @param file file that this RemoteFile object should represent.
	 */
	private LinkedRemoteFile(
		LinkedRemoteFile parent,
		RemoteFileInterface file,
		FtpConfig cfg) {
		if(file.getName().indexOf('*') != -1) {
			throw new IllegalArgumentException("Illegal character in filename");
		}
		_ftpConfig = cfg;
		_lastModified = file.lastModified();
		_length = file.length();
		if (_length == -1)
			throw new IllegalArgumentException("length() == -1 for " + file);

		if (!file.isFile() && !file.isDirectory()) {
			logger.log(
				Level.FATAL,
				"File is not a file nor a directory: " + file,
				new Throwable());
		}

		_isDeleted = file.isDeleted();
		//_owner = new String(file.getUsername());
		//_group = new String(file.getGroupname());
		setOwner(file.getUsername());
		setGroup(file.getGroupname());
		_checkSum = file.getCheckSumCached();
		if (file.isFile()) {
			//			_slaves =
			//				Collections.synchronizedCollection(
			//					new ArrayList(file.getSlaves()));
			_slaves = new Vector(file.getSlaves());
			if (_slaves == null) {
				throw new IllegalArgumentException(
					"slaves == null for " + file);
			}
		}

		if (parent == null) {
			_name = EMPTY_STRING;
		} else {
			_name = new String(file.getName());
		}

		/* serialize directory*/
		_parent = parent;

		//		if (remoteSlave != null) {
		//			slaves.add(remoteSlave);
		//		}

		if (file.isDirectory()) {
			RemoteFileInterface dir[] = file.listFiles();
			//			if (name != "" && dir.length == 0)
			//				throw new FatalException(
			//					"Constructor called with empty dir: " + file);
			_files = Collections.synchronizedMap(new Hashtable(dir.length));
			Stack dirstack = new Stack();
			for (int i = 0; i < dir.length; i++) {
				RemoteFileInterface file2 = dir[i];
				if (file2.isDirectory()) {
					dirstack.push(file2);
					continue;
				}
				_files.put(
					file2.getName(),
					new LinkedRemoteFile(this, file2, _ftpConfig));
			}

			Iterator i = dirstack.iterator();
			while (i.hasNext()) {
				RemoteFileInterface file2 = (RemoteFileInterface) i.next();
				String filename = file2.getName();
				_files.put(
					filename,
					new LinkedRemoteFile(this, file2, _ftpConfig));
			}
		}
	}

	/**
	 * Creates a root directory (parent == null) that FileRemoteFile or JDOMRemoteFile is merged on.
	 * 
	 * Also called with null ConnectionManager from slave
	 */
	public LinkedRemoteFile(RemoteFileInterface file, FtpConfig cfg)
		throws IOException {
		this(null, file, cfg);
	}

	/**
	 * Updates lastMofidied() on this directory, use putFile() to avoid it.
	 */
	public LinkedRemoteFile addFile(RemoteFile file) {
		_lastModified = System.currentTimeMillis();
		return putFile(file);
	}
	public void addSlave(RemoteSlave slave) {
		if (_slaves == null) //!isDirectory()
			throw new IllegalStateException("Cannot addSlave() on a non-directory");
		assert slave != null;

		// we get lots of duplicate adds when merging and the slave is already in the file database
		if (_slaves.contains(slave)) {
			return;
		}
		_slaves.add(slave);
	}

	public LinkedRemoteFile createDirectory(
		String owner,
		String group,
		String fileName)
		throws ObjectExistsException {
		LinkedRemoteFile existingfile = (LinkedRemoteFile) _files.get(fileName);
		if (existingfile != null) {
			throw new ObjectExistsException(
				fileName + " already exists in this directory");
		}
		//		for (Iterator i = slaves.iterator(); i.hasNext();) {
		//			RemoteSlave slave = (RemoteSlave) i.next();
		//			try {
		//				slave.getSlave().mkdir(owner, getPath() + "/" + fileName);
		//			} catch (RemoteException ex) {
		//				slave.handleRemoteException(ex);
		//			}
		//		}
		LinkedRemoteFile file =
			new LinkedRemoteFile(
				this,
				//new DirectoryRemoteFile(this, owner, group, fileName),
				new StaticRemoteFile(null, fileName, owner, group, 0L, System.currentTimeMillis()),
				_ftpConfig);
		//file.addSlaves(getSlaves());
		_files.put(file.getName(), file);
		logger.debug("Created directory " + file);
		_lastModified = System.currentTimeMillis();
		return file;
	}

	public void delete() {
		_isDeleted = true;
		if (isDirectory()) {
			for (Iterator iter = getFiles().iterator(); iter.hasNext();) {
				LinkedRemoteFile myFile = (LinkedRemoteFile) iter.next();
				myFile.delete();
			}
			try {
				if (dirSize() == 0) {
					Object ret = getParentFile().getMap().remove(getName());
					assert ret != null;
				}
			} catch (FileNotFoundException ex) {
				logger.log(
					Level.FATAL,
					"FileNotFoundException on getParentFile()",
					ex);
			}
			return;
		} else {

			synchronized (_slaves) {
				for (Iterator iter = _slaves.iterator(); iter.hasNext();) {
					RemoteSlave rslave = (RemoteSlave) iter.next();
					Slave slave;
					try {
						slave = rslave.getSlave();
					} catch (NoAvailableSlaveException ex) {
						logger.info("slave not available for deletion");
						continue;
					}
					try {
						System.out.println(
							"DELETE: " + rslave.getName() + ": " + getPath());
						slave.delete(getPath());
						// throws RemoteException, IOException
						iter.remove();
					} catch(FileNotFoundException ex) {
						iter.remove();
						logger.warn(getPath()+" missing on "+rslave.getName()+" during delete, assumed deleted");
					} catch (RemoteException ex) {
						rslave.handleRemoteException(ex);
						continue;
					} catch (IOException ex) {
						logger.log(
							Level.FATAL,
							"IOException deleting file on slave "
								+ rslave.getName(),
							ex);
						continue;
					}
				}
			}

			if (_slaves.size() == 0) {
				try {
					getParentFile().getMap().remove(getName());
				} catch (FileNotFoundException ex) {
					logger.log(
						Level.FATAL,
						"FileNotFoundException on getParentFile()",
						ex);
				}
			} else {
				logger.log(
					Level.INFO,
					getPath()
						+ " queued for deletion, remaining slaves:"
						+ _slaves);
			}
		}
	}

	public long dirSize() {
		if (_files == null)
			throw new IllegalStateException("Cannot be called on a non-directory");
		return _files.size();
	}

	public boolean equals(Object obj) {
		if (obj instanceof LinkedRemoteFile
			&& ((LinkedRemoteFile) obj).getPath().equals(getPath())) {
			return true;
		}
		return false;
	}

	public RemoteSlave getASlave(char direction)
		throws NoAvailableSlaveException {
		return SlaveManagerImpl.getASlave(
			getAvailableSlaves(),
			direction,
			_ftpConfig);
	}

	public RemoteSlave getASlaveForDownload()
		throws NoAvailableSlaveException {
		return getASlave(Transfer.TRANSFER_SENDING_DOWNLOAD);
	}

	public Collection getAvailableSlaves() throws NoAvailableSlaveException {
		ArrayList availableSlaves = new ArrayList();
		for (Iterator iter = getSlaves().iterator(); iter.hasNext();) {
			RemoteSlave rslave = (RemoteSlave) iter.next();
			if (!rslave.isAvailable())
				continue;
			availableSlaves.add(rslave);
		}
		if (availableSlaves.isEmpty()) {
			throw new NoAvailableSlaveException(
				getPath() + " has 0 slaves online");
		}
		return availableSlaves;
	}

	/**
	 * @see net.sf.drftpd.remotefile.RemoteFile#getCheckSum()
	 * @throws net.sf.drftpd.master.NoAvailableSlaveException
	 */
	public long getCheckSum() throws IOException {
		if (_checkSum == 0 && _length != 0) {
			try {
				_checkSum = getCheckSumFromSlave();
			} catch (NoAvailableSlaveException ex) {
			} // checkSum will still be 0L, return that...
		}
		return _checkSum;
	}
	public long getCheckSumCached() {
		return _checkSum;
	}
	/**
	 * Returns the checksum 0L if the checksum cannot be read.
	 * @return
	 * @throws NoAvailableSlaveException
	 */
	public long getCheckSumFromSlave()
		throws NoAvailableSlaveException, IOException {
		RemoteSlave slave;
		while (true) {
			slave = getASlaveForDownload();
			try {
				_checkSum = slave.getSlave().checkSum(getPath());
				// throws IOException
			} catch (RemoteException ex) {
				slave.handleRemoteException(ex);
				continue;
			}
			return _checkSum;
		}

	}

	/**
	 * Returns fileName contained in this directory.
	 * 
	 * @param fileName
	 * @throws FileNotFoundException if fileName doesn't exist in the files Map
	 */
	public LinkedRemoteFile getFile(String fileName)
		throws FileNotFoundException {
		LinkedRemoteFile file = (LinkedRemoteFile) _files.get(fileName);
		if (file == null)
			throw new FileNotFoundException("No such file or directory");
		if (file.isDeleted())
			throw new FileNotFoundException("File is queued for deletion");
		return file;
	}

	public Collection getFiles() {
		return getFilesMap().values();
	}

	public Map getFilesMap() {
		Hashtable ret = new Hashtable(_files);

		for (Iterator iter = ret.values().iterator(); iter.hasNext();) {
			LinkedRemoteFile file = (LinkedRemoteFile) iter.next();
			if (file.isDeleted())
				iter.remove();
		}
		return ret;
		//return Collections.unmodifiableMap(ret);
	}

	/* (non-Javadoc)
	 * @see net.sf.drftpd.remotefile.RemoteFileInterface#getGroupname()
	 */
	public String getGroupname() {
		if (_group == null || _group.equals(EMPTY_STRING))
			return "drftpd";
		return _group;
	}

	/** return files;
	 */
	public Map getMap() {
		return _files;
	}

	/** return name;
	 */
	public String getName() {
		return _name;
	}

	/**
	 * @return getParentFile().getPath()
	 * @see java.io.File#getParent()
	 * @see net.sf.drftpd.remotefile.RemoteFile#getParent()
	 */
	public String getParent() throws FileNotFoundException {
		return getParentFile().getPath();
	}

	/**
	 * @see java.io.File#getParentFile()
	 */
	public LinkedRemoteFile getParentFile() throws FileNotFoundException {
		if (_parent == null)
			throw new FileNotFoundException("root directory has no parent");
		return _parent;
	}

	public LinkedRemoteFile getParentFileNull() {
		return _parent;
	}

	public String getPath() {
		StringBuffer path = new StringBuffer();
		LinkedRemoteFile parent = this;

		while (true) {
			if (parent.getName().length() == 0)
				break;
			path.insert(0, "/" + parent.getName());
			try {
				parent = parent.getParentFile();
				// throws FileNotFoundException
			} catch (FileNotFoundException ex) {
				break;
			}
		}
		if (path.length() == 0)
			return "/";
		return path.toString();
	}

	public LinkedRemoteFile getRoot() {
		LinkedRemoteFile root = this;
		try {
			while (true)
				root = root.getParentFile();
		} catch (FileNotFoundException ex) {
		}
		return root;
	}
	public SFVFile getSFVFile()
		throws IOException, FileNotFoundException, NoAvailableSlaveException {
		if (sfvFile != null)
			return sfvFile;
		synchronized (this) {
			if (sfvFile == null) {
				while (true) {
					RemoteSlave slave = getASlaveForDownload();
					try {
						sfvFile = slave.getSlave().getSFVFile(getPath());
						//throws RemoteException
						sfvFile.setCompanion(this);
						break;
					} catch (ConnectException ex) {
						slave.handleRemoteException(ex);
					}
				}
				throw new NoAvailableSlaveException("No available slaves");
			}
			if (sfvFile.size() == 0)
				throw new FileNotFoundException("sfv file contains no checksum entries");
			return sfvFile;
		}
	}

	/** returns slaves. May return null if a directory.
	 */
	public Collection getSlaves() {
		if (_slaves == null)
			throw new IllegalStateException("getSlaves() on non-directory");
		return _slaves;
	}

	/* (non-Javadoc)
	 * @see net.sf.drftpd.remotefile.RemoteFileInterface#getUsername()
	 */
	public String getUsername() {
		if (_owner == null || _owner.equals(EMPTY_STRING))
			return "nobody";
		return _owner;
	}

	/**
	 * @return
	 */
	public long getXferspeed() {
		return length() / (getXfertime() / 1000);
	}
	/**
	 * @return xfertime in milliseconds
	 */
	public long getXfertime() {
		return _xfertime;
	}

	public boolean hasFile(String filename) {
		return _files.containsKey(filename);
	}

	public boolean hasOfflineSlaves() {
		if (isFile()) {
			for (Iterator iter = getSlaves().iterator(); iter.hasNext();) {
				RemoteSlave rslave = (RemoteSlave) iter.next();
				assert rslave != null;
				if (!rslave.isAvailable())
					return true;
			}
		} else if (isDirectory()) {
			for (Iterator iter = getFiles().iterator(); iter.hasNext();) {
				if (((LinkedRemoteFile) iter.next()).hasOfflineSlaves())
					return true;
			}
		}
		return false;
	}
	
	public boolean hasSlave(RemoteSlave slave) {
		return _slaves.contains(slave);
	}

	/**
	 * Does file have online slaves?
	 * 
	 * @return Always true for directories
	 */
	public boolean isAvailable() {
		if (isDirectory())
			return true;
		for (Iterator iter = getSlaves().iterator(); iter.hasNext();) {
			RemoteSlave rslave = (RemoteSlave) iter.next();
			assert rslave != null;
			if (rslave.isAvailable())
				return true;
		}
		return false;
	}
	/** return isDeleted;
	 */
	public boolean isDeleted() {
		return _isDeleted;
	}

	/**
	 * @see net.sf.drftpd.remotefile.RemoteFile#isDirectory()
	 */
	public boolean isDirectory() {
		return _files != null;
	}

	/* (non-Javadoc)
	 * @see net.sf.drftpd.remotefile.RemoteFile#isFile()
	 */
	public boolean isFile() {
		return _files == null;
	}

	/* (non-Javadoc)
	 * @see net.sf.drftpd.remotefile.RemoteFile#lastModified()
	 */
	public long lastModified() {
		return _lastModified;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#finalize()
	 * 
	 * ouch, we only want this to happen on the master root. what to do?
	 * And we don't want the extra dependency on the slave.
	 * It's so nice to have it save before it exits.
	 */
	//	protected void finalize() throws Throwable {
	//		super.finalize();
	//		if (this.parent == null) {
	//			SlaveManagerImpl.saveFilesXML(XMLSerialize.serialize(this));
	//		}
	//	}
	/**
	 * @see net.sf.drftpd.remotefile.RemoteFile#length()
	 */
	public long length() {
		//		if (isDirectory()) {
		//			long length = 0;
		//			for (Iterator iter = getFiles().iterator(); iter.hasNext();) {
		//				LinkedRemoteFile element = (LinkedRemoteFile) iter.next();
		//				length += element.length();
		//			}
		//			return length;
		//		}
		return _length;
	}

	public RemoteFileInterface[] listFiles() {
		if (!isDirectory())
			throw new RuntimeException(getPath() + " is not a directory");
		return (LinkedRemoteFile[]) getFilesMap().values().toArray(
			new LinkedRemoteFile[0]);
	}

	public LinkedRemoteFile lookupFile(String path)
		throws FileNotFoundException {

		Object[] ret = lookupNonExistingFile(path);

		//logger.info("ret[0] = " + ret[0] + " ret[1] = " + ret[1]);
		if (ret[1] != null)
			throw new FileNotFoundException(path + ": File not found");
		return (LinkedRemoteFile) ret[0];
	}

	/**
	 * 
	 * @param path
	 * @return new Object[] {LinkedRemoteFile file, String path};
	 * path is null if path exists
	 */
	public Object[] lookupNonExistingFile(String path) {
		if (path == null)
			throw new IllegalArgumentException("null path not allowed");
		LinkedRemoteFile currFile = this;

		if (path.charAt(0) == '/')
			currFile = getRoot();

		//check for leading ~
		if (path.length() == 1 && path.equals("~")) {
			currFile = getRoot();
			path = EMPTY_STRING;
		} else if (path.length() >= 2 && path.substring(0, 2).equals("~/")) {
			currFile = getRoot();
			path = path.substring(2);
		}

		StringTokenizer st = new StringTokenizer(path, "/");
		while (st.hasMoreTokens()) {
			String currFileName = st.nextToken();
			if (currFileName.equals("."))
				continue;
			if (currFileName.equals("..")) {
				try {
					currFile = currFile.getParentFile();
				} catch (FileNotFoundException ex) {
				}
				continue;
			}
			LinkedRemoteFile nextFile;
			try {
				nextFile = (LinkedRemoteFile) currFile.getFile(currFileName);
			} catch (FileNotFoundException ex) {
				StringBuffer remaining = new StringBuffer(currFileName);
				if (st.hasMoreElements()) {
					remaining.append('/').append(st.nextToken(EMPTY_STRING));
				}
				return new Object[] { currFile, remaining.toString()};
			}
			currFile = nextFile;
		}
		return new Object[] { currFile, null };
	}

	/**
	 * Returns path for a non-existing file. Performs path normalization and returns an absolute path
	 * @param path
	 * @return
	 */
	public String lookupPath(String path) {
		Object[] ret = lookupNonExistingFile(path);
		if (ret[1] == null) {
			return ((LinkedRemoteFile) ret[0]).getPath();
		}
		return ((LinkedRemoteFile) ret[0]).getPath() + "/" + ((String) ret[1]);
	}
	public SFVFile lookupSFVFile()
		throws IOException, FileNotFoundException, NoAvailableSlaveException {
		if (!isDirectory())
			throw new IllegalStateException("lookupSFVFile must be called on a directory");

		for (Iterator iter = getFiles().iterator(); iter.hasNext();) {
			LinkedRemoteFile myFile = (LinkedRemoteFile) iter.next();
			if (myFile.getName().toLowerCase().endsWith(".sfv")) {
				return myFile.getSFVFile();
				// throws IOException, NoAvailableSlaveException
			}
		}
		throw new FileNotFoundException("no sfv file in directory");

	}

	public LinkedRemoteFile putFile(RemoteFile file) {
		//validate
		if (!file.isDirectory()) {
			//isFile() that is
			assert file.getSlaves() != null : file.toString();
			for (Iterator iter = file.getSlaves().iterator();
				iter.hasNext();
				) {
				RemoteSlave element = (RemoteSlave) iter.next();
				assert element != null;
			}
		}

		LinkedRemoteFile linkedfile =
			new LinkedRemoteFile(this, file, _ftpConfig);
		_files.put(linkedfile.getName(), linkedfile);
		return linkedfile;
	}

	/**
	 * Merges two RemoteFile directories.
	 * If duplicates exist, the slaves are added to this object and the file-attributes of the oldest file (lastModified) are kept.
	 */
	public void remerge(LinkedRemoteFile mergedir, RemoteSlave rslave) {
		assert _ftpConfig != null : this;

		if (!isDirectory()) {
			throw new IllegalArgumentException(
				"merge() called on a non-directory: "
					+ this
					+ " argument: "
					+ mergedir);
		}
		if (!mergedir.isDirectory()) {
			throw new IllegalArgumentException(
				"argument is not a directory: " + mergedir + " this: " + this);
		}
		Map map = getMap();
		//Collection mergefiles = mergedir.getFiles();
		if (mergedir.getFiles() == null) {
			throw new FatalException(
				"dir.getFiles() returned null: " + this +", " + mergedir);
		}

		// add all files from mergedir
		for (Iterator i = mergedir.getFiles().iterator(); i.hasNext();) {
			LinkedRemoteFile mergefile = (LinkedRemoteFile) i.next();

			if (mergefile.isDirectory() && mergefile.length() == 0) {
				logger.log(
					Level.FATAL,
					"Attempt to add empty directory: "
						+ mergefile
						+ " from "
						+ rslave.getName());
			}

			LinkedRemoteFile file =
				(LinkedRemoteFile) _files.get(mergefile.getName());
			// two scenarios:, local file [does not] exists
			if (file == null) {
				// local file does not exist, just put it in the hashtable
				if (!mergefile.isDirectory()) {
					mergefile.addSlave(rslave);
				} else {
					setRSlaveAndConfig(mergefile, _ftpConfig, rslave);
				}
				mergefile._ftpConfig = _ftpConfig;
				mergefile._parent = this;
				map.put(mergefile.getName(), mergefile);
				logger.warn(
					mergefile.getPath() + " added from " + rslave.getName());
			} else {
				if (file.isDeleted()) {
					logger.log(
						Level.WARN,
						"Queued delete on "
							+ rslave
							+ " for file "
							+ mergefile);
					if (!mergefile.isDirectory())
						mergefile.addSlave(rslave);
					mergefile.delete();
					continue;
				}
				if (mergefile.isFile()
					&& file.length() != mergefile.length()) {
					Collection filerslaves = mergefile.getSlaves();

					//					try {
					//						SFVFile sfvFile = lookupSFVFile();
					//						long checksum = sfvFile.getChecksum(file.getName());
					//						if(mergefile.getCheckSum())
					//					} catch(ObjectNotFoundException e) {
					//					} catch(Throwable t) {
					//					}
					if ((filerslaves.size() == 1
						&& filerslaves.contains(rslave))
						|| file.length() == 0) {
						file._length = mergefile.length();
						file._checkSum = 0L;
						//file.getCheckSum(true);
						file._lastModified = mergefile.lastModified();
					} else if (mergefile.length() == 0) {
						logger.log(
							Level.INFO,
							"Deleting 0byte " + mergefile + " on " + rslave);
						try {
							rslave.getSlave().delete(mergefile.getPath());
						} catch (PermissionDeniedException ex) {
							logger.log(
								Level.FATAL,
								"Error deleting 0byte file on " + rslave,
								ex);
						} catch (Exception e) {
							throw new FatalException(e);
						}
						continue;
					} else {
						//rename file...
						try {
							rslave.getSlave().rename(
								getPath() + "/" + mergefile.getName(),
								getPath(),
								mergefile.getName() + "." + rslave.getName());
							mergefile._name =
								mergefile.getName() + "." + rslave.getName();
							mergefile.addSlave(rslave);
							_files.put(mergefile.getName(), mergefile);
							logger.log(
								Level.WARN,
								"2 or more slaves contained same file with different sizes, renamed to "
									+ mergefile.getName());
							continue;
						} catch (Exception e) {
							throw new FatalException(e);
						}
					}
				}

				// 4 scenarios: new/existing file/directory
				if (mergefile.isDirectory()) {
					if (!file.isDirectory())
						throw new RuntimeException(
							"!!! ERROR: Directory/File conflict: "
								+ mergefile
								+ " and "
								+ file
								+ " from "
								+ rslave.getName());
					// is a directory -- dive into directory and start merging
					file.remerge(mergefile, rslave);
				} else {
					file.addSlave(rslave);
					if (file.isDirectory())
						throw new RuntimeException(
							"!!! ERROR: File/Directory conflict: "
								+ mergefile
								+ " and "
								+ file
								+ " from "
								+ rslave.getName());
				}
			} // file != null
		}

		// remove all slaves not in mergedir.getFiles()
		// unmerge() gets called on all files & directories not on slave
		for (Iterator i = getFiles().iterator(); i.hasNext();) {
			LinkedRemoteFile file = (LinkedRemoteFile) i.next();

			if (!mergedir.hasFile(file.getName())) {
				file.unmerge(rslave);
			}
		}
	}

	public boolean removeSlave(RemoteSlave slave) {
		if (_slaves != null)
			return _slaves.remove(slave);
		return false;
	}

	/**
	 * Renames this file
	 * 
	 * Sanity checks performed:
	 *   destination file does not exist
	 * @throws IllegalFileNameException, FileExistsException, FileNotFoundException
	 */
	public void renameTo(String toDirPath, String toName)
		throws IOException, FileNotFoundException {
		if (toDirPath.charAt(0) != '/')
			throw new RuntimeException("renameTo() must be given an absolute path as argument");

		// throws FileNotFoundException
		/*if (getParentFile().getMap().get(to) != null) {
			throw new FileExistsException("Target file exists");
		}*/

		LinkedRemoteFile toDir = lookupFile(toDirPath);
		// throws FileNotFoundException

		if (toName.indexOf('/') != -1)
			throw new RuntimeException("Cannot rename to non-existing directory");

		String fromName = getName();
		assert _ftpConfig != null;
		if (hasOfflineSlaves())
			throw new IOException("File has offline slaves");
		if (isDirectory()) {
			for (Iterator iter =
				_ftpConfig.getSlaveManager().getSlaves().iterator();
				iter.hasNext();
				) {
				RemoteSlave rslave = (RemoteSlave) iter.next();
				Slave slave;
				try {
					slave = rslave.getSlave();
				} catch (NoAvailableSlaveException e) {
					//trust that hasOfflineSlaves() did a good job and no files are present on offline slaves
					continue;
				}
				try {
					slave.rename(getPath(), toDirPath, toName);
				} catch (RemoteException ex) {
					rslave.handleRemoteException(ex);
				} catch (IOException ex) {
					logger.log(
						Level.FATAL,
						"IOException in renameTo() for dir for "
							+ rslave.getName(),
						ex);
				}
			}
		} else {
			for (Iterator iter =
				_ftpConfig.getSlaveManager().getSlaves().iterator();
				iter.hasNext();
				) {
				RemoteSlave rslave = (RemoteSlave) iter.next();
				Slave slave;
				try {
					slave = rslave.getSlave();
				} catch (NoAvailableSlaveException ex) {
					continue;
				}
				try {
					slave.rename(getPath(), toDirPath, toName);
					// throws RemoteException, IOException
				} catch (RemoteException ex) {
					rslave.handleRemoteException(ex);
				} catch (IOException ex) {
					logger.log(
						Level.FATAL,
						"IO error from "
							+ rslave.getName()
							+ " on a file in LinkedRemoteFile",
						ex);
				}
			}
		}

		//Object[] ret = lookupNonExistingFile(to);
		//
		//String toName = (String) ret[1];
		//		if (toName == null)
		//			throw new ObjectExistsException("Target already exists");

		try {
			getParentFile()._files.remove(fromName);
		} catch (FileNotFoundException ex) {
			logger.log(
				Level.FATAL,
				"FileNotFoundException on getParentFile() on this in rename",
				ex);
		}

		_parent = toDir;
		toDir.getMap().put(toName, this);
		_name = toName;
	}

	/**
	 * @param torslave RemoteSlave to replicate to.
	 */
	public void replicate(final RemoteSlave torslave)
		throws NoAvailableSlaveException, IOException {

		final RemoteSlave fromslave = getASlaveForDownload();
		Transfer fromtransfer = fromslave.getSlave().listen();
		final Transfer totransfer =
			torslave.getSlave().connect(
				fromslave.getInetAddress(),
				fromtransfer.getLocalPort());

		Thread t = new Thread(new Runnable() {
			private Exception exception = null;
			public void run() {
				try {
					totransfer.receiveFile(
						getParentFile().getPath(),
						getName(),
						0L);
				} catch (RemoteException e) {
					torslave.handleRemoteException(e);
					logger.warn(EMPTY_STRING, e);
				} catch (FileNotFoundException e) {
					throw new FatalException(e);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		});
		t.run();
		fromtransfer.sendFile(getPath(), 'I', 0, false);
	}

	/**
	 * @param l
	 */
	public void setCheckSum(long l) {
		_checkSum = l;
	}
	public void setGroup(String group) {
		_group = group.intern();
	}

	public void setLastModified(long lastModified) {
		_lastModified = lastModified;
	}

	public void setLength(long length) {
		_length = length;
	}
	public void setOwner(String owner) {
		_owner = owner.intern();
	}

	private void setRSlaveAndConfig(
		LinkedRemoteFile dir,
		FtpConfig cfg,
		RemoteSlave rslave) {
		for (Iterator iter = dir.getFiles().iterator(); iter.hasNext();) {
			LinkedRemoteFile file = (LinkedRemoteFile) iter.next();
			file._ftpConfig = cfg;
			if (file.isDirectory()) {
				setRSlaveAndConfig(file, cfg, rslave);
			} else {
				file.addSlave(rslave);
			}
		}
	}

	/**
	 * @param l
	 */
	public void setXfertime(long l) {
		_xfertime = l;
	}

	/**
	 * @see java.lang.Object#toString()
	 */
	public String toString() {
		StringBuffer ret = new StringBuffer();
		ret.append("LinkedRemoteFile[\"" + this.getName() + "\",");
		if (isFile()) {
			ret.append("xfertime:" + _xfertime);
		}
		if (this.isDeleted())
			ret.append("deleted,");
		//ret.append(slaves);
		if (_slaves != null) {
			Iterator i = _slaves.iterator();
			//			Enumeration e = slaves.elements();
			ret.append("slaves:[");
			// HACK: How can one find out the endpoint without using regexps?
			//Pattern p = Pattern.compile("endpoint:\\[(.*?):.*?\\]");
			while (i.hasNext()) {
				RemoteSlave rslave = (RemoteSlave) i.next();
				if (rslave == null)
					throw new FatalException("There's a null in rslaves");
				ret.append(rslave.getName());
				if (!rslave.isAvailable())
					ret.append("-OFFLINE");
				if (i.hasNext())
					ret.append(",");
			}
			ret.append("]");
		}
		if (isDirectory())
			ret.append("[directory(" + _files.size() + ")]");
		ret.append("]");
		return ret.toString();
	}

	public void unmerge(RemoteSlave rslave) { //LinkedRemoteFile files[] = listFiles();
		removeSlave(rslave);
		if (!isDirectory())
			return;
		for (Iterator i = _files.values().iterator(); i.hasNext();) {
			LinkedRemoteFile file = (LinkedRemoteFile) i.next();
			if (file.isDirectory()) {
				file.unmerge(rslave);
				if (file.isDeleted() && file.getFilesMap().size() == 0)
					i.remove();
			} else {
				if (file.removeSlave(rslave)) {
					logger.warn(
						file.getPath() + " deleted from " + rslave.getName());
				}
				//it's safe to remove it as it has no slaves.
				if (file.getSlaves().size() == 0)
					i.remove();
			}
		}
		//		//we only called on directories
		//		if (isFile() && getSlaves().size() == 0) {
		//			delete();
		//		}
	}

}
