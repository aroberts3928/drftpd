/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package net.sf.drftpd.util;

import net.sf.drftpd.NoAvailableSlaveException;
import net.sf.drftpd.master.BaseFtpConnection;

import org.apache.log4j.Logger;
import org.drftpd.SFVFile;
import org.drftpd.SFVFile.SFVStatus;
import org.drftpd.commands.Reply;
import org.drftpd.id3.ID3Tag;
import org.drftpd.remotefile.LinkedRemoteFile;
import org.drftpd.remotefile.LinkedRemoteFileInterface;
import org.drftpd.remotefile.RemoteFileInterface;
import org.drftpd.remotefile.StaticRemoteFile;
import org.tanesha.replacer.FormatterException;
import org.tanesha.replacer.ReplacerEnvironment;
import org.tanesha.replacer.SimplePrintf;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;


/**
 * @author mog
 * @version $Id$
 */
public class ListUtils {
    private static final Logger logger = Logger.getLogger(ListUtils.class);
    public static final String PADDING = "          ";

    private ListUtils() {
    }

    public static boolean isLegalFileName(String fileName) {
        if (fileName == null) {
            throw new RuntimeException();
        }

        return (fileName.indexOf("/") == -1) && (fileName.indexOf('*') == -1) &&
        !fileName.equals(".") && !fileName.equals("..");
    }

    public static List list(LinkedRemoteFileInterface directoryFile,
        BaseFtpConnection conn) {
        try {
			return list(directoryFile, conn, null);
		} catch (IOException e) {
			logger.info("IOException while lising directory "+directoryFile.getPath(),e);
			return new ArrayList();
		}
    }

    public static List list(LinkedRemoteFileInterface dir,
        BaseFtpConnection conn, Reply response) throws IOException {
        ArrayList tempFileList = new ArrayList<RemoteFileInterface>(dir.getFiles());
        ArrayList listFiles = new ArrayList<RemoteFileInterface>();
        int numOnline = 0;
        int numTotal = 0;
        boolean id3found = false;
        ID3Tag mp3tag = null;

        Properties zsCfg = new Properties();
        FileInputStream zsFile = new FileInputStream("conf/zipscript.conf");
        zsCfg.load(zsFile);
        zsFile.close();

        for (Iterator iter = tempFileList.iterator(); iter.hasNext();) {
            LinkedRemoteFile element = (LinkedRemoteFile) iter.next();

            if ((conn.getGlobalContext().getConnectionManager()
                         .getGlobalContext().getConfig() != null) &&
                    !conn.getGlobalContext().getConnectionManager()
                             .getGlobalContext().getConfig().checkPrivPath(conn.getUserNull(),
                        element)) {
                // don't add it
                continue;
            }

            if (element.isFile() &&
                    element.getName().toLowerCase().endsWith(".mp3") &&
                    (id3found == false)) {
                try {
                    mp3tag = element.getID3v1Tag();
                    id3found = true;
                } catch (FileNotFoundException e) {
                    logger.warn("FileNotFoundException: " + element.getPath(), e);
                } catch (IOException e) {
                    logger.warn("IOException: " + element.getPath(), e);
                } catch (NoAvailableSlaveException e) {
                    logger.warn("NoAvailableSlaveException: " +
                        element.getPath(), e);
                }
            }

            //			if (element.isDirectory()) { // can slow listing
            //				try {
            //					int filesleft =
            //						element.lookupSFVFile().getStatus().getMissing();
            //					if (filesleft != 0)
            //						listFiles.add(
            //							new StaticRemoteFile(
            //								null,
            //								element.getName()
            //									+ "-"
            //									+ filesleft
            //									+ "-FILES-MISSING",
            //								"drftpd",
            //								"drftpd",
            //								0L,
            //								dir.lastModified()));
            //				} catch (IOException e) {
            //				} // errors getting SFV? FINE! We don't care!
            //				// is a directory
            //				//				numOnline++; // do not want to include directories in the file count
            //				//				numTotal++;
            //				listFiles.add(element);
            //				continue;
            //			} else if (
    		boolean offlineEnabled = zsCfg.getProperty("files.missing.enabled").equalsIgnoreCase("true");
            if (!element.isAvailable() && offlineEnabled) { // directories are always available
                try {
    				ReplacerEnvironment env = new ReplacerEnvironment();
    				env.add("ofilename",element.getName());
                	String oFileName = SimplePrintf.jprintf(zsCfg.getProperty("files.offline.filename"), env);
                	listFiles.add(new StaticRemoteFile(Collections.EMPTY_LIST,
	                        oFileName, element.getUsername(),
	                        element.getGroupname(), element.length(),
	                        element.lastModified()));
                    numTotal++;
				} catch (FormatterException e1) {
					logger.warn("",e1);
				}

                // -OFFLINE and "ONLINE" files will both be present until someoe implements
                // a way to reupload OFFLINE files.
                // It could be confusing to the user and/or client if the file doesn't exist, but you can't upload it. 
            }

            //else element is a file, and is online
            numOnline++;
            numTotal++;
            listFiles.add(element);
        }

        String statusDirName = null;

        try {
            SFVFile sfvfile = dir.lookupSFVFile();
            SFVStatus sfvstatus = sfvfile.getStatus();
			ReplacerEnvironment env = new ReplacerEnvironment();

            if (sfvfile.size() != 0) {

                if (sfvstatus.getMissing() != 0) {
					env.add("missing.number","" + sfvstatus.getMissing());
					env.add("missing.percent","" + (sfvstatus.getMissing() * 100) / sfvfile.size());
					env.add("missing",SimplePrintf.jprintf(zsCfg.getProperty("statusbar.missing"),env));
                } else {
                	env.add("missing","");
                }

				if (sfvstatus.getPresent() == 0) {
					env.add("complete.number","0");
					env.add("complete.percent","0");						
				} else {
					env.add("complete.total","" + sfvfile.size());
					env.add("complete.number", "" + sfvstatus.getPresent());
					env.add("complete.percent","" + (sfvstatus.getPresent() * 100) / sfvfile.size());
				}
				env.add("complete",SimplePrintf.jprintf(zsCfg.getProperty("statusbar.complete"),env));

                if (sfvstatus.getOffline() != 0) {
					env.add("offline.number","" + sfvstatus.getOffline());
					env.add("offline.percent",""+ (sfvstatus.getOffline() * 100) / sfvstatus.getPresent());
					env.add("online.number","" + sfvstatus.getPresent());
					env.add("online.percent","" + (sfvstatus.getAvailable() * 100) / sfvstatus.getPresent());
					env.add("offline",SimplePrintf.jprintf(zsCfg.getProperty("statusbar.offline"),env));
                } else {
                	env.add("offline","");
                }

                //mp3tag info added by teflon artist, album, title, genre, year
                if (id3found) {
                	env.add("artist",mp3tag.getArtist().trim());
                	env.add("album",mp3tag.getAlbum().trim());
                	env.add("title", mp3tag.getTitle().trim());
                	env.add("genre", mp3tag.getGenre().trim());
                	env.add("year", mp3tag.getYear().trim());
                	env.add("id3tag",SimplePrintf.jprintf(zsCfg.getProperty("statusbar.id3tag"),env));
                } else {
                	env.add("id3tag","");
                }

                statusDirName = SimplePrintf.jprintf(zsCfg.getProperty("statusbar.format"),env);

                if (statusDirName == null) {
                    throw new RuntimeException();
                }

                boolean statusbarEnabled = zsCfg.getProperty("statusbar.enabled").equalsIgnoreCase("true");
                if (statusbarEnabled) {
                	listFiles.add(new StaticRemoteFile(null, statusDirName,
                        "drftpd", "drftpd", 0L, dir.lastModified()));
                }
                
                boolean missingEnabled = zsCfg.getProperty("files.missing.enabled").equalsIgnoreCase("true");
                if (missingEnabled) {
                    for (Iterator iter = sfvfile.getNames().iterator();
                    	iter.hasNext();) {
                    	String filename = (String) iter.next();

                    	if (!dir.hasFile(filename)) {
                    		//listFiles.remove()
                    		env.add("mfilename",filename);
                    		listFiles.add(new StaticRemoteFile(
                    				Collections.EMPTY_LIST, 
									SimplePrintf.jprintf(zsCfg.getProperty("files.missing.filename"),env),
									"drftpd", "drftpd", 0L, dir.lastModified()));
                    	}
                    }
                }
            }
        } catch (NoAvailableSlaveException e) {
            logger.warn("No available slaves for SFV file", e);
        } catch (FileNotFoundException e) {
            // no sfv file in directory - just skip it
        } catch (IOException e) {
            logger.warn("IO error loading SFV file", e);
        } catch (Throwable e) {
            logger.warn("zipscript error", e);
        }

        return listFiles;
    }

    public static String padToLength(String value, int length) {
        if (value.length() >= length) {
            return value;
        }

        if (PADDING.length() < length) {
            throw new RuntimeException("padding must be longer than length");
        }

        return PADDING.substring(0, length - value.length()) + value;
    }
}
