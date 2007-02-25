/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 *
 * DrFTPD is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * DrFTPD; if not, write to the Free Software Foundation, Inc., 59 Temple Place,
 * Suite 330, Boston, MA 02111-1307 USA
 */
package org.drftpd.commands.dataconnection;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ResourceBundle;
import java.util.StringTokenizer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.apache.log4j.Logger;
import org.drftpd.Bytes;
import org.drftpd.Checksum;
import org.drftpd.GlobalContext;
import org.drftpd.PassiveConnection;
import org.drftpd.commandmanager.CommandInterface;
import org.drftpd.commandmanager.CommandRequest;
import org.drftpd.commandmanager.CommandRequestInterface;
import org.drftpd.commandmanager.CommandResponse;
import org.drftpd.commandmanager.StandardCommandManager;
import org.drftpd.event.TransferEvent;
import org.drftpd.exceptions.FileExistsException;
import org.drftpd.exceptions.NoAvailableSlaveException;
import org.drftpd.exceptions.SlaveUnavailableException;
import org.drftpd.master.BaseFtpConnection;
import org.drftpd.master.FtpReply;
import org.drftpd.master.RemoteSlave;
import org.drftpd.master.TransferState;
import org.drftpd.slave.ConnectInfo;
import org.drftpd.slave.RemoteIOException;
import org.drftpd.slave.Transfer;
import org.drftpd.slave.TransferFailedException;
import org.drftpd.slave.TransferStatus;
import org.drftpd.usermanager.UserFileException;
import org.drftpd.util.FtpRequest;
import org.drftpd.vfs.FileHandle;
import org.drftpd.vfs.ListUtils;
import org.drftpd.vfs.ObjectNotValidException;
import org.tanesha.replacer.ReplacerEnvironment;

/**
 * @author mog
 * @author zubov
 * @author djb61
 * @version $Id$
 */
public class DataConnectionHandler extends CommandInterface {
    private static final Logger logger = Logger.getLogger(DataConnectionHandler.class);

    private ResourceBundle _bundle;

    public void initialize(String method, String pluginName, StandardCommandManager cManager) {
    	super.initialize(method, pluginName, cManager);
    	_bundle = ResourceBundle.getBundle(this.getClass().getName());
    	_featReplies = populateFeat(method);
    }

    public CommandResponse doAUTH(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}
    	SSLContext ctx = GlobalContext.getGlobalContext().getSSLContext();
        if (ctx == null) {
            response = new CommandResponse(400, "TLS not configured");
            doPostHooks(request, response);
            return response;
        }

        BaseFtpConnection conn = request.getConnection();
        Socket s = conn.getControlSocket();

        //reply success
        conn.getControlWriter().write(new FtpReply(234, request.getOriginalCommand()
        		+ request.getArgument() + " successful").toString());
        conn.getControlWriter().flush();
        SSLSocket s2 = null;
        try {
            s2 = (SSLSocket) ctx.getSocketFactory().createSocket(s,
                    s.getInetAddress().getHostAddress(), s.getPort(), true);
            conn.setControlSocket(s2);
            s2.setUseClientMode(false);
            s2.setSoTimeout(10000);
            s2.startHandshake();
        } catch (IOException e) {
            logger.warn("", e);
            if (s2 != null) {
            	try {
            		s2.close();
            	} catch (IOException e2){
            		logger.debug("error closing SSLSocket connection");
            	}
            }
            conn.stop(e.getMessage());

            doPostHooks(request, null);
            return null;
		}
        s2 = null;

        doPostHooks(request, null);
        return null;
    }

    /**
     * <code>MODE &lt;SP&gt; <mode-code> &lt;CRLF&gt;</code><br>
     *
     * The argument is a single Telnet character code specifying the data
     * transfer modes described in the Section on Transmission Modes.
     */
    public CommandResponse doMODE(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}

    	// argument check
        if (!request.hasArgument()) {
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
        	doPostHooks(request, response);
            return response;
        }

        if (request.getArgument().equalsIgnoreCase("S")) {
        	response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
        	doPostHooks(request, response);
            return response;
        }

        response = StandardCommandManager.genericResponse("RESPONSE_504_COMMAND_NOT_IMPLEMENTED_FOR_PARM");
    	doPostHooks(request, response);
        return response;
    }

    /**
     * <code>PASV &lt;CRLF&gt;</code><br>
     *
     * This command requests the server-DTP to "listen" on a data port (which is
     * not its default data port) and to wait for a connection rather than
     * initiate one upon receipt of a transfer command. The response to this
     * command includes the host and port address this server is listening on.
     */
    public CommandResponse doPASVandCPSV(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}

    	BaseFtpConnection conn = request.getConnection();
    	TransferState ts = conn.getTransferState();
    	ts.setPasv(true);
        if (!ts.isPreTransfer()) {
        	reset(conn);
        	response = new CommandResponse(500,
                "You need to use a client supporting PRET (PRE Transfer) to use PASV");
        	doPostHooks(request, response);
        	return response;
        }
        
        if (request.getOriginalCommand().equals("CPSV")) {
        	conn.getTransferState().setSSLHandshakeClientMode(true);
        }

        InetSocketAddress address = null;

        if (ts.isLocalPreTransfer()) {
        	// setup a PassiveConnection for a local transfer, LIST/NLST
        	PassiveConnection pc;
			try {
				pc = new PassiveConnection(ts.getSendFilesEncrypted() ? conn.getGlobalContext().getSSLContext() : null, conn.getGlobalContext().getPortRange(), false);
			} catch (IOException e1) {
				reset(conn);
				response = new CommandResponse(500, e1.getMessage());
		        doPostHooks(request, response);
		        return response;
			}
            try {
				address = new InetSocketAddress(conn.getGlobalContext()
						.getConfig().getPasvAddress(), pc
						.getLocalPort());
			} catch (NullPointerException e) {
				address = new InetSocketAddress(conn.getControlSocket()
						.getLocalAddress(), pc.getLocalPort());
			}
			ts.setLocalPassiveConnection(pc);
        } else {
        	RemoteSlave slave = null;
        	ConnectInfo ci = null;
        	if (ts.isPASVDownload()) {
 				while (slave == null) {
					try {
						slave = conn.getGlobalContext()
								.getSlaveSelectionManager().getASlave(
										ts.getTransferFile().getAvailableSlaves(),
										Transfer.TRANSFER_SENDING_DOWNLOAD,
										conn, ts.getTransferFile());
						String index = slave.issueListenToSlave(
								ts.getSendFilesEncrypted(), ts
										.getSSLHandshakeClientMode());
						ci = slave.fetchTransferResponseFromIndex(index);
			            ts.setTransfer(slave.getTransfer(ci.getTransferIndex()));
			            address = new InetSocketAddress(slave.getPASVIP(),ts.getTransfer().getAddress().getPort());
					} catch (FileNotFoundException e) {
						// Strange, since we validated it existed in PRET, but this could definitely happen
						reset(conn);
						response = StandardCommandManager.genericResponse("RESPONSE_550_REQUESTED_ACTION_NOT_TAKEN");
						doPostHooks(request, response);
				        return response;
					} catch (NoAvailableSlaveException e) {
						reset(conn);
						response = StandardCommandManager.genericResponse("RESPONSE_450_SLAVE_UNAVAILABLE");
						doPostHooks(request, response);
				        return response;
					} catch (SlaveUnavailableException e) {
						// make it loop till it finds a good one
						slave = null;
					} catch (RemoteIOException e) {
						if (slave != null) {
							slave.setOffline("Slave could not listen for a connection");
						}
		                logger.error("Slave could not listen for a connection", e);
		                // make it loop until it finds a good one
		                slave = null;
					}
				}
        	} else if (ts.isPASVUpload()) {
        		while (slave == null) {
					try {
						slave = conn.getGlobalContext()
								.getSlaveSelectionManager().getASlave(
										conn.getGlobalContext()
												.getSlaveManager()
												.getAvailableSlaves(),
										Transfer.TRANSFER_RECEIVING_UPLOAD,
										conn, ts.getTransferFile());
						String index = slave.issueListenToSlave(
								ts.getSendFilesEncrypted(), ts
										.getSSLHandshakeClientMode());
						ci = slave.fetchTransferResponseFromIndex(index);
			            ts.setTransfer(slave.getTransfer(ci.getTransferIndex()));
			            address = new InetSocketAddress(slave.getPASVIP(),ts.getTransfer().getAddress().getPort());
					} catch (NoAvailableSlaveException e) {
						reset(conn);
						response = StandardCommandManager.genericResponse("RESPONSE_450_SLAVE_UNAVAILABLE");
						doPostHooks(request, response);
				        return response;
					} catch (SlaveUnavailableException e) {
						// make it loop till it finds a good one
						slave = null;
					} catch (RemoteIOException e) {
						if (slave != null) {
							slave
									.setOffline("Slave could not listen for a connection");
						}
						logger.error("Slave could not listen for a connection",
								e);
						// make it loop until it finds a good one
						slave = null;
					}
				}
        	} else {
        		response = StandardCommandManager.genericResponse("RESPONSE_502_COMMAND_NOT_IMPLEMENTED");
				doPostHooks(request, response);
		        return response;
        	}
        	ts.setTransferSlave(slave);
        }
        
        if (conn.getRequest().getCommand().equals("CPSV")) {
        	// can only reset it if the transfer was setup with CPSV
        	ts.setSSLHandshakeClientMode(false);
        }

        if (address.getAddress() == null || address.getAddress().getHostAddress() == null) {
        	response = new CommandResponse(500,
        			"Address is unresolvable, check pasv_addr setting on " + ts.getTransferSlave().getName());
			doPostHooks(request, response);
	        return response;
        }
        
        String addrStr = address.getAddress().getHostAddress().replace('.', ',') +
            ',' + (address.getPort() >> 8) + ',' + (address.getPort() & 0xFF);
        response = new CommandResponse(227, "Entering Passive Mode (" + addrStr + ").");
        response.addComment("Using " + (ts.isLocalPreTransfer() ? "master" : ts.getTransferSlave().getName()) + " for upcoming transfer");
        doPostHooks(request, response);
        return response;
    }

    public CommandResponse doPBSZ(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}
        String cmd = request.getArgument();

        if ((cmd == null) || !cmd.equals("0")) {
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }

        response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
		doPostHooks(request, response);
        return response;
    }

    /**
     * <code>PORT &lt;SP&gt; <host-port> &lt;CRLF&gt;</code><br>
     *
     * The argument is a HOST-PORT specification for the data port to be used in
     * data connection. There are defaults for both the user and server data
     * ports, and under normal circumstances this command and its reply are not
     * needed. If this command is used, the argument is the concatenation of a
     * 32-bit internet host address and a 16-bit TCP port address. This address
     * information is broken into 8-bit fields and the value of each field is
     * transmitted as a decimal number (in character string representation). The
     * fields are separated by commas. A port command would be:
     *
     * PORT h1,h2,h3,h4,p1,p2
     *
     * where h1 is the high order 8 bits of the internet host address.
     */
    public CommandResponse doPORT(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}

        InetAddress clientAddr = null;

        // argument check
        if (!request.hasArgument()) {
            //Syntax error in parameters or arguments
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }

        StringTokenizer st = new StringTokenizer(request.getArgument(), ",");

        if (st.countTokens() != 6) {
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }

        // get data server
        String dataSrvName = st.nextToken() + '.' + st.nextToken() + '.' +
            st.nextToken() + '.' + st.nextToken();

        try {
            clientAddr = InetAddress.getByName(dataSrvName);
        } catch (UnknownHostException ex) {
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }

        BaseFtpConnection conn = request.getConnection();
        String portHostAddress = clientAddr.getHostAddress();
        String clientHostAddress = conn.getControlSocket().getInetAddress()
                                       .getHostAddress();

        if ((portHostAddress.startsWith("192.168.") &&
                !clientHostAddress.startsWith("192.168.")) ||
                (portHostAddress.startsWith("10.") &&
                !clientHostAddress.startsWith("10."))) {
            response = new CommandResponse(501);
            response.addComment("==YOU'RE BEHIND A NAT ROUTER==");
            response.addComment(
                "Configure the firewall settings of your FTP client");
            response.addComment("  to use your real IP: " +
                conn.getControlSocket().getInetAddress().getHostAddress());
            response.addComment("And set up port forwarding in your router.");
            response.addComment(
                "Or you can just use a PRET capable client, see");
            response.addComment("  http://drftpd.org/ for PRET capable clients");
            
            reset(conn);
            doPostHooks(request, response);
	        return response;
        }

        int clientPort;

        // get data server port
        try {
            int hi = Integer.parseInt(st.nextToken());
            int lo = Integer.parseInt(st.nextToken());
            clientPort = (hi << 8) | lo;
        } catch (NumberFormatException ex) {
        	reset(conn);
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }

        conn.getTransferState().setPortAddress(new InetSocketAddress(clientAddr, clientPort));
        

        response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
        
        if (portHostAddress.startsWith("127.")) {
            response.addComment(
            		"Ok, but distributed transfers won't work with local addresses");
        }

        //Notify the user that this is not his IP.. Good for NAT users that
        // aren't aware that their IP has changed.
        if (!clientAddr.equals(conn.getControlSocket().getInetAddress())) {
            response.addComment(
                "FXP allowed. If you're not FXPing then set your IP to " +
                conn.getControlSocket().getInetAddress().getHostAddress() +
                " (usually in firewall settings)");
        }
        // get slave from PRET
        TransferState ts = conn.getTransferState();
    	try {
        if (ts.isPreTransfer()) {
				// do SlaveSelection now since we're using PRET Active transfers
				char direction = ts.getDirection(ts.getPretRequest());
				if (direction == Transfer.TRANSFER_SENDING_DOWNLOAD) {
					ts.setTransferSlave(conn.getGlobalContext()
							.getSlaveSelectionManager().getASlave(
									conn.getGlobalContext().getSlaveManager()
											.getAvailableSlaves(),
									Transfer.TRANSFER_SENDING_DOWNLOAD, conn,
									ts.getTransferFile()));
				} else if (direction == Transfer.TRANSFER_RECEIVING_UPLOAD) {
					ts.setTransferSlave(conn.getGlobalContext()
							.getSlaveSelectionManager().getASlave(
									conn.getGlobalContext().getSlaveManager()
											.getAvailableSlaves(),
									Transfer.TRANSFER_RECEIVING_UPLOAD, conn,
									ts.getTransferFile()));
				}
				response.addComment("Using "
						+ (ts.isLocalPreTransfer() ? "master:"
								+ GlobalContext.getGlobalContext().getConfig()
										.getPasvAddress() : ts
								.getTransferSlave().getName()
								+ ":" + ts.getTransferSlave().getPASVIP())
						+ " for upcoming transfer");
			}


		} catch (SlaveUnavailableException e) {
			// I don't want to deal with this at the PORT command, let's let it
			// error at transfer()
		} catch (NoAvailableSlaveException e) {
			// I don't want to deal with this at the PORT command, let's let it
			// error at transfer()
		}
		doPostHooks(request, response);
        return response;
    }

    public CommandResponse doPRET(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}
    	BaseFtpConnection conn = request.getConnection();
    	reset(conn);
        TransferState ts = conn.getTransferState();
        ts.setPreTransferRequest(new FtpRequest(request.getArgument()));
        
        if (ts.isLocalPreTransfer()) {
        	response = new CommandResponse(200, "OK, planning to use master for upcoming LIST transfer");
            doPostHooks(request, response);
            return response;
        }
    	response = setTransferFileFromPRETRequest(request);
    	doPostHooks(request, response);
        return response;
    }
    
    public CommandResponse setTransferFileFromPRETRequest(CommandRequest request) {
    	CommandResponse response;
    	BaseFtpConnection conn = request.getConnection();
    	TransferState ts = conn.getTransferState();
        FtpRequest ghostRequest = ts.getPretRequest();
        if (ghostRequest == null) {
        	throw new IllegalStateException("PRET was not called before setTransferFileFromPRETRequest()");
        }
        String cmd = ghostRequest.getCommand();
		if (cmd.equals("RETR")) {
	        	FileHandle file = null;
	    		try {
					file = conn.getCurrentDirectory().getFile(ts.getPretRequest().getArgument());
				} catch (FileNotFoundException e) {
					reset(conn);
					response = StandardCommandManager.genericResponse("RESPONSE_550_REQUESTED_ACTION_NOT_TAKEN");
			        return response;
				} catch (ObjectNotValidException e) {
					reset(conn);
					response = new CommandResponse(550, "Requested target is not a file");
		            doPostHooks(request, response);
		            return response;
				}
				ts.setTransferFile(file);
				response = new CommandResponse(200, "OK, planning for upcoming download");
	            return response;
	        } else if (cmd.equals("STOR")) {
	        	FileHandle file = null;
	    		try {
					file = conn.getCurrentDirectory().getFile(ts.getPretRequest().getArgument());
				} catch (FileNotFoundException e) {
					// this is good, do nothing
					// should be null already, but just for my (current) sanity
					file = null;
				} catch (ObjectNotValidException e) {
					// this is not good, file exists
	            	// until we can upload multiple instances of files
					reset(conn);
					response = StandardCommandManager.genericResponse("RESPONSE_553_REQUESTED_ACTION_NOT_TAKEN_FILE_EXISTS");
			        return response;
				}
				if (file != null) {
					// this is not good, file exists
	            	// until we can upload multiple instances of files
					reset(conn);
					response = StandardCommandManager.genericResponse("RESPONSE_553_REQUESTED_ACTION_NOT_TAKEN_FILE_EXISTS");
			        return response;
				}
				file = conn.getCurrentDirectory().getNonExistentFileHandle(ts.getPretRequest().getArgument());

	            if (!ListUtils.isLegalFileName(file.getName())) {
	            	reset(conn);
	            	response = StandardCommandManager.genericResponse("RESPONSE_553_REQUESTED_ACTION_NOT_TAKEN");
			        return response;
	            }
	            ts.setTransferFile(file);
	            response = new CommandResponse(200, "OK, planning for upcoming upload");
	            return response;
	        } else {
	        	response = StandardCommandManager.genericResponse("RESPONSE_504_COMMAND_NOT_IMPLEMENTED_FOR_PARM");
		        return response;
	        }
    }
    
    public CommandResponse doSSCN(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}
    	BaseFtpConnection conn = request.getConnection();
    	if (conn.getGlobalContext().getSSLContext() == null) {
    		response = new CommandResponse(500, "TLS not configured");
    		doPostHooks(request, response);
            return response;
        }
        
        if (!(conn.getControlSocket() instanceof SSLSocket)) {
        	response = new CommandResponse(500, "You are not on a secure channel");
    		doPostHooks(request, response);
            return response;
        }
        
        if (!conn.getTransferState().getSendFilesEncrypted()) {
        	response = new CommandResponse(500, "SSCN only works for encrypted transfers");
    		doPostHooks(request, response);
            return response;
        }
        
		if (conn.getRequest().hasArgument()) {
			if (conn.getRequest().getArgument().equalsIgnoreCase("ON")) {
				conn.getTransferState().setSSLHandshakeClientMode(true);
			}
			if (conn.getRequest().getArgument().equalsIgnoreCase("OFF")) {
				conn.getTransferState().setSSLHandshakeClientMode(false);
			}
		}
		response = new CommandResponse(220,  "SSCN:"
				+ (conn.getTransferState().getSSLHandshakeClientMode() ? "CLIENT" : "SERVER") + " METHOD");
		doPostHooks(request, response);
        return response;
	}

    public CommandResponse doPROT(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}
    	BaseFtpConnection conn = request.getConnection();
    	if (conn.getGlobalContext().getSSLContext() == null) {
    		response = new CommandResponse(500, "TLS not configured");
    		doPostHooks(request, response);
            return response;
        }
        
        if (!(conn.getControlSocket() instanceof SSLSocket)) {
        	response = new CommandResponse(500, "You are not on a secure channel");
    		doPostHooks(request, response);
            return response;
        }

        if (!request.hasArgument()) {
            //clear
            conn.getTransferState().setSendFilesEncrypted(false);

            response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
    		doPostHooks(request, response);
            return response;
        }

        if (!request.hasArgument() || (request.getArgument().length() != 1)) {
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }

        switch (Character.toUpperCase(request.getArgument().charAt(0))) {
        case 'C':

            //clear
        	conn.getTransferState().setSendFilesEncrypted(false);

        	response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
    		doPostHooks(request, response);
            return response;

        case 'P':

            //private
        	conn.getTransferState().setSendFilesEncrypted(true);

        	response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
    		doPostHooks(request, response);
            return response;

        default:
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }
    }

    /**
     * <code>REST &lt;SP&gt; <marker> &lt;CRLF&gt;</code><br>
     *
     * The argument field represents the server marker at which file transfer is
     * to be restarted. This command does not cause file transfer but skips over
     * the file to the specified data checkpoint. This command shall be
     * immediately followed by the appropriate FTP service command which shall
     * cause file transfer to resume.
     */
    public CommandResponse doREST(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}
    	BaseFtpConnection conn = request.getConnection();

        // argument check
        if (!request.hasArgument()) {
        	reset(conn);
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }

        String skipNum = request.getArgument();
        long resumePosition = 0L;

        try {
            resumePosition = Long.parseLong(skipNum);
        } catch (NumberFormatException ex) {
        	reset(conn);
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }

        if (resumePosition < 0) {
            resumePosition = 0;
            reset(conn);
            response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }
        conn.getTransferState().setResumePosition(resumePosition);

        response = StandardCommandManager.genericResponse("RESPONSE_350_PENDING_FURTHER_INFORMATION");
		doPostHooks(request, response);
        return response;
    }

/*    private Reply doSITE_RESCAN(BaseFtpConnection conn) {
        FtpRequest request = conn.getRequest();
        boolean forceRescan = (request.hasArgument() &&
            request.getArgument().equalsIgnoreCase("force"));
        LinkedRemoteFileInterface directory = conn.getCurrentDirectory();
        SFVFile sfv;

        try {
            sfv = conn.getCurrentDirectory().lookupSFVFile();
        } catch (Exception e) {
            return new Reply(200, "Error getting SFV File: " +
                e.getMessage());
        }

        PrintWriter out = conn.getControlWriter();

        for (Iterator i = sfv.getEntries().entrySet().iterator(); i.hasNext();) {
            Map.Entry entry = (Map.Entry) i.next();
            String fileName = (String) entry.getKey();
            Long checkSum = (Long) entry.getValue();
            LinkedRemoteFileInterface file;

            try {
                file = directory.lookupFile(fileName);
            } catch (FileNotFoundException ex) {
                out.write("200- SFV: " +
                    Checksum.formatChecksum(checkSum.longValue()) + " SLAVE: " +
                    fileName + " MISSING" + BaseFtpConnection.NEWLINE);

                continue;
            }

            String status;
            long fileCheckSum = 0;

            try {
                if (forceRescan) {
                    fileCheckSum = file.getCheckSumFromSlave();
                } else {
                    fileCheckSum = file.getCheckSum();
                }
            } catch (NoAvailableSlaveException e1) {
                out.println("200- " + fileName + "SFV: " +
                    Checksum.formatChecksum(checkSum.longValue()) +
                    " SLAVE: OFFLINE");

                continue;
            } catch (IOException ex) {
                out.print("200- " + fileName + " SFV: " +
                    Checksum.formatChecksum(checkSum.longValue()) +
                    " SLAVE: IO error: " + ex.getMessage());

                continue;
            }

            if (fileCheckSum == 0L) {
                status = "FAILED - failed to checksum file";
            } else if (checkSum.longValue() == fileCheckSum) {
                status = "OK";
            } else {
                status = "FAILED - checksum mismatch";
            }

            out.println("200- " + fileName + " SFV: " +
                Checksum.formatChecksum(checkSum.longValue()) + " SLAVE: " +
                Checksum.formatChecksum(checkSum.longValue()) + " " + status);

            continue;
        }

        return Reply.RESPONSE_200_COMMAND_OK;
    }
*/
    public CommandResponse doSITE_XDUPE(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}
    	response = StandardCommandManager.genericResponse("RESPONSE_502_COMMAND_NOT_IMPLEMENTED");
 		doPostHooks(request, response);
        return response;

        //		resetState();
        //
        //		if (!request.hasArgument()) {
        //			if (this.xdupe == 0) {
        //				out.println("200 Extended dupe mode is disabled.");
        //			} else {
        //				out.println(
        //					"200 Extended dupe mode " + this.xdupe + " is enabled.");
        //			}
        //			return;
        //		}
        //
        //		short myXdupe;
        //		try {
        //			myXdupe = Short.parseShort(request.getArgument());
        //		} catch (NumberFormatException ex) {
        //			out.print(FtpResponse.RESPONSE_501_SYNTAX_ERROR);
        //			return;
        //		}
        //
        //		if (myXdupe > 0 || myXdupe < 4) {
        //			out.print(
        //				FtpResponse.RESPONSE_504_COMMAND_NOT_IMPLEMENTED_FOR_PARM);
        //			return;
        //		}
        //		this.xdupe = myXdupe;
        //		out.println("200 Activated extended dupe mode " + myXdupe + ".");
    }

    /**
     * <code>STRU &lt;SP&gt; &lt;structure-code&gt; &lt;CRLF&gt;</code><br>
     *
     * The argument is a single Telnet character code specifying file structure.
     */
    public CommandResponse doSTRU(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}

        // argument check
        if (!request.hasArgument()) {
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }

        if (request.getArgument().equalsIgnoreCase("F")) {
        	response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
			doPostHooks(request, response);
	        return response;
        }

        response = StandardCommandManager.genericResponse("RESPONSE_504_COMMAND_NOT_IMPLEMENTED_FOR_PARM");
		doPostHooks(request, response);
        return response;
    }

    /**
     * <code>SYST &lt;CRLF&gt;</code><br>
     *
     * This command is used to find out the type of operating system at the
     * server.
     */
    public CommandResponse doSYST(CommandRequest request) {
        /*
         * String systemName = System.getProperty("os.name"); if(systemName ==
         * null) { systemName = "UNKNOWN"; } else { systemName =
         * systemName.toUpperCase(); systemName = systemName.replace(' ', '-'); }
         * String args[] = {systemName};
         */
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}
    	response = StandardCommandManager.genericResponse("RESPONSE_215_SYSTEM_TYPE");
		doPostHooks(request, response);
        return response;

        //String args[] = { "UNIX" };
        //out.write(ftpStatus.getResponse(215, request, user, args));
    }

    /**
     * <code>TYPE &lt;SP&gt; &lt;type-code&gt; &lt;CRLF&gt;</code><br>
     *
     * The argument specifies the representation type.
     */
    public CommandResponse doTYPE(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}
    	BaseFtpConnection conn = request.getConnection();

        // get type from argument
        if (!request.hasArgument()) {
        	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
			doPostHooks(request, response);
	        return response;
        }

        // set it
        if (conn.getTransferState().setType(request.getArgument().charAt(0))) {
        	response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
			doPostHooks(request, response);
	        return response;
        }

        response = StandardCommandManager.genericResponse("RESPONSE_504_COMMAND_NOT_IMPLEMENTED_FOR_PARM");
		doPostHooks(request, response);
        return response;
    }

/*    *//**
     * Get the data socket.
     *
     * Used by LIST and NLST and MLST.
     *//*
    public Socket getDataSocket() throws IOException {
        Socket dataSocket;

        // get socket depending on the selection
        if (isPort()) {
            try {
				ActiveConnection ac = new ActiveConnection(_encryptedDataChannel ? _ctx : null, _portAddress, _SSLHandshakeClientMode);
                dataSocket = ac.connect();
            } catch (IOException ex) {
                logger.warn("Error opening data socket", ex);
                dataSocket = null;
                throw ex;
            }
        } else if (isPasv()) {
            try {
                dataSocket = _passiveConnection.connect();
            } finally {
				if (_passiveConnection != null) {
					_passiveConnection.abort();
	                _passiveConnection = null;
				}
            }
        } else {
            throw new IllegalStateException("Neither PASV nor PORT");
        }
		// Already done since we are using ActiveConnection and PasvConnection
        dataSocket.setSoTimeout(Connection.TIMEOUT); // 15 seconds timeout

        if (dataSocket instanceof SSLSocket) {
            SSLSocket ssldatasocket = (SSLSocket) dataSocket;
            ssldatasocket.setUseClientMode(false);
            ssldatasocket.startHandshake();
        }

        return dataSocket;
    }*/

    private String[] populateFeat(String method) {
    	boolean sslContext = GlobalContext.getGlobalContext().getSSLContext() != null;
    	if ("doAUTH".equals(method)) {
    		return sslContext ? new String[] { "AUTH TLS" } : null;
    	}
    	if ("doPASVandCPSV".equals(method)) {
    		return sslContext ? new String[] { "CPSV" } : null;
    	}
    	if ("doPBSZ".equals(method)) {
    		return sslContext ? new String[] { "PBSZ" } : null;
    	}
    	if ("doSSCN".equals(method)) {
    		return sslContext ? new String[] { "SSCN" } : null;
    	}
    	if ("doPRET".equals(method)) {
    		return new String[] { "PRET" };
    	}
    	return null;
    }
    
    public String getHelp(String cmd) {
        ResourceBundle bundle = ResourceBundle.getBundle(DataConnectionHandler.class.getName());
        if ("".equals(cmd))
            return bundle.getString("help.general")+"\n";
        else if("rescan".equals(cmd))
            return bundle.getString("help.rescan")+"\n";
        else
            return "";
    }

    protected synchronized void reset(BaseFtpConnection conn) {
    	conn.getTransferState().reset();
    }

    /**
     * <code>STOU &lt;CRLF&gt;</code><br>
     *
     * This command behaves like STOR except that the resultant file is to be
     * created in the current directory under a name unique to that directory.
     * The 250 Transfer Started response must include the name generated.
     */

    //TODO STOU

    /*
     * public void doSTOU(FtpRequest request, PrintWriter out) {
     *  // reset state variables resetState();
     *  // get filenames String fileName =
     * user.getVirtualDirectory().getAbsoluteName("ftp.dat"); String
     * physicalName = user.getVirtualDirectory().getPhysicalName(fileName); File
     * requestedFile = new File(physicalName); requestedFile =
     * IoUtils.getUniqueFile(requestedFile); fileName =
     * user.getVirtualDirectory().getVirtualName(requestedFile.getAbsolutePath());
     * String args[] = {fileName};
     *  // check permission
     * if(!user.getVirtualDirectory().hasCreatePermission(fileName, false)) {
     * out.write(ftpStatus.getResponse(550, request, user, null)); return; }
     *  // now transfer file data out.print(FtpResponse.RESPONSE_150_OK);
     * InputStream is = null; OutputStream os = null; try { Socket dataSoc =
     * mDataConnection.getDataSocket(); if (dataSoc == null) {
     * out.write(ftpStatus.getResponse(550, request, user, args)); return; }
     *
     *
     * is = dataSoc.getInputStream(); os = user.getOutputStream( new
     * FileOutputStream(requestedFile) );
     *
     * StreamConnector msc = new StreamConnector(is, os);
     * msc.setMaxTransferRate(user.getMaxUploadRate()); msc.setObserver(this);
     * msc.connect();
     *
     * if(msc.hasException()) { out.write(ftpStatus.getResponse(451, request,
     * user, null)); return; } else {
     * mConfig.getStatistics().setUpload(requestedFile, user,
     * msc.getTransferredSize()); }
     *
     * out.write(ftpStatus.getResponse(226, request, user, null));
     * mDataConnection.reset(); out.write(ftpStatus.getResponse(250, request,
     * user, args)); } catch(IOException ex) {
     * out.write(ftpStatus.getResponse(425, request, user, null)); } finally {
     * IoUtils.close(is); IoUtils.close(os); mDataConnection.reset(); } }
     */

    /**
     * <code>RETR &lt;SP&gt; &lt;pathname&gt; &lt;CRLF&gt;</code><br>
     *
     * This command causes the server-DTP to transfer a copy of the file,
     * specified in the pathname, to the server- or user-DTP at the other end of
     * the data connection. The status and contents of the file at the server
     * site shall be unaffected.
     *
     * RETR 125, 150 (110) 226, 250 425, 426, 451 450, 550 500, 501, 421, 530
     * <p>
     * <code>STOR &lt;SP&gt; &lt;pathname&gt; &lt;CRLF&gt;</code><br>
     *
     * This command causes the server-DTP to accept the data transferred via the
     * data connection and to store the data as a file at the server site. If
     * the file specified in the pathname exists at the server site, then its
     * contents shall be replaced by the data being transferred. A new file is
     * created at the server site if the file specified in the pathname does not
     * already exist.
     *
     * STOR 125, 150 (110) 226, 250 425, 426, 451, 551, 552 532, 450, 452, 553
     * 500, 501, 421, 530
     *
     * ''zipscript?? renames bad uploads to .bad, how do we handle this with
     * resumes?
     */
    //TODO add APPE support
    public CommandResponse transfer(CommandRequest request) {
    	CommandResponse response;
    	request = doPreHooks(request);
    	if(!request.isAllowed()) {
    		response = request.getDeniedResponse();
    		if (response == null) {
    			response = StandardCommandManager.genericResponse("RESPONSE_530_ACCESS_DENIED");
    		}
    		doPostHooks(request, response);
    		return response;
    	}
    	BaseFtpConnection conn = request.getConnection();
    	TransferState ts = conn.getTransferState();
        ReplacerEnvironment env = new ReplacerEnvironment();
        if (!ts.getSendFilesEncrypted() &&
                conn.getGlobalContext().getConfig().checkPermission("denydatauncrypted", conn.getUserNull())) {
        	reset(conn);
        	response = new CommandResponse(530, "USE SECURE DATA CONNECTION");
    		doPostHooks(request, response);
            return response;
        }

        try {
            char direction = ts.getDirection(new FtpRequest(
            		request.getOriginalCommand()));
            String cmd = request.getOriginalCommand();
            boolean isStor = cmd.equals("STOR");
            boolean isRetr = cmd.equals("RETR");
            boolean isAppe = cmd.equals("APPE");
            boolean isStou = cmd.equals("STOU");
            String eventType = isRetr ? "RETR" : "STOR";

            if (isAppe || isStou) {
            	response = StandardCommandManager.genericResponse("RESPONSE_502_COMMAND_NOT_IMPLEMENTED");
         		doPostHooks(request, response);
                return response;
            }

            // argument check
            if (!request.hasArgument()) {
            	// reset(); already done in finally block
            	response = StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
    			doPostHooks(request, response);
    	        return response;
            }
            
//          Checks maxsim up/down
            // _simup OR _simdown = 0, exempt
            int comparison = 0;
            int count = conn.transferCounter(direction);
            env.add("maxsim", count);

            if (direction == Transfer.TRANSFER_RECEIVING_UPLOAD) {
            	comparison =  conn.getUserNull().getMaxSimUp();
                env.add("direction", "upload");
            } else {
            	comparison =  conn.getUserNull().getMaxSimDown();
                env.add("direction", "download");
            }

            if (comparison != 0 && count >= comparison) {
            	response = new CommandResponse(550,
            			jprintf(_bundle, "transfer.err.maxsim", env, request.getUser()));
        		doPostHooks(request, response);
                return response;
            }

            // get filenames

            if (isRetr && ts.isPort()) {
            	if (ts.getTransferFile() == null) {
					try {
						ts.setTransferFile(conn.getCurrentDirectory().getFile(
								request.getArgument()));
					} catch (FileNotFoundException e) {
						response = StandardCommandManager.genericResponse("RESPONSE_550_REQUESTED_ACTION_NOT_TAKEN");
		    			doPostHooks(request, response);
		    	        return response;
					} catch (ObjectNotValidException e) {
						response = new CommandResponse(550, "Argument is not a file");
			    		doPostHooks(request, response);
			            return response;
					}
				} // else { ts.getTransferFile() is set, this is a PRET action
            }
            /*            if (isRetr) {
            	ts.getTransferFile().isFile()
                try {
                    _transferFile = conn.getCurrentDirectory().lookupFile(request.getArgument());

                    if (!_transferFile.isFile()) {
                    	// reset(); already done in finally block
                        return new Reply(550, "Not a plain file");
                    }

                    targetDir = _transferFile.getParentFileNull();
                    targetFileName = _transferFile.getName();
                } catch (FileNotFoundException ex) {
                	// reset(); already done in finally block
                    return new Reply(550, ex.getMessage());
                }
            } else if (isStor) {
                LinkedRemoteFile.NonExistingFile ret = conn.getCurrentDirectory()
                                                           .lookupNonExistingFile(conn.getGlobalContext()
                                                                                      .getConfig()
                                                                                      .getFileName(request.getArgument()));
                targetDir = ret.getFile();
                targetFileName = ret.getPath();

                if (ret.exists()) {
                    // target exists, this could be overwrite or resume
                    //TODO overwrite & resume files.
                	// reset(); already done in finally block
                    return Reply.RESPONSE_553_REQUESTED_ACTION_NOT_TAKEN_FILE_EXISTS;

                    //_transferFile = targetDir;
                    //targetDir = _transferFile.getParent();
                    //if(_transfereFile.getOwner().equals(getUser().getUsername()))
                    // {
                    //	// allow overwrite/resume
                    //}
                    //if(directory.isDirectory()) {
                    //	return FtpReply.RESPONSE_550_REQUESTED_ACTION_NOT_TAKEN;
                    //}
                }

                if (!ListUtils.isLegalFileName(targetFileName) ||
                        !conn.getGlobalContext().getConfig().checkPathPermission("privpath", conn.getUserNull(), targetDir, true)) {
                	// reset(); already done in finally block
                    return new Reply(553,
                        "Requested action not taken. File name not allowed.");
                }

                //do our zipscript sfv checks
                String checkName = targetFileName.toLowerCase();
                ZipscriptConfig zsCfg = conn.getGlobalContext().getZsConfig();
                boolean SfvFirstEnforcedPath = zsCfg.checkSfvFirstEnforcedPath(targetDir, 
                		conn.getUserNull()); 
                try {
                    SFVFile sfv = conn.getCurrentDirectory().lookupSFVFile();
                    if (checkName.endsWith(".sfv") && 
                    	!zsCfg.multiSfvAllowed()) {
                        return new Reply(533,
                        	"Requested action not taken. Multiple SFV files not allowed.");
                    }
                    if (SfvFirstEnforcedPath && 
                    		!zsCfg.checkAllowedExtension(checkName)) {
                		// filename not explicitly permitted, check for sfv entry
                        boolean allow = false;
                        if (zsCfg.restrictSfvEnabled()) {
                            for (Iterator iter = sfv.getNames().iterator(); iter.hasNext();) {
                                String name = (String) iter.next();
                                if (name.toLowerCase().equals(checkName)) {
                                    allow = true;
                                    break;
                                }
                            }
                            if (!allow) {
                                return new Reply(533,
                                	"Requested action not taken. File not found in sfv.");
                            }
                        }
                    }
                } catch (FileNotFoundException e1) {
                    // no sfv found in dir 
                	if ( !zsCfg.checkAllowedExtension(checkName) && 
                			SfvFirstEnforcedPath ) {
                		// filename not explicitly permitted
                		// ForceSfvFirst is on, and file is in an enforced path.
                        return new Reply(533,
                        	"Requested action not taken. You must upload sfv first.");
                	}
                } catch (IOException e1) {
                    //error reading sfv, do nothing
                } catch (NoAvailableSlaveException e1) {
                    //sfv not online, do nothing
                }
            } else {
            	// reset(); already done in finally block
            	throw UnhandledCommandException.create(
            			DataConnectionHandler.class, request);
            }

            // check access
            if (!conn.getGlobalContext().getConfig().checkPathPermission("privpath", conn.getUserNull(), targetDir, true)) {
            	// reset(); already done in finally block
                return new Reply(550,
                    request.getArgument() + ": No such file");
            }
*/
/*            switch (direction) {
            case Transfer.TRANSFER_SENDING_DOWNLOAD:
                if (!conn.getGlobalContext().getConfig().checkPathPermission(
						"download", conn.getUserNull(),
						ts.getTransferFile().getParent())) {
					// reset(); already done in finally block
					return Reply.RESPONSE_530_ACCESS_DENIED;
				}

                break;

            case Transfer.TRANSFER_RECEIVING_UPLOAD:

                if (!conn.getGlobalContext().getConfig().checkPathPermission(
						"upload", conn.getUserNull(),
						ts.getTransferFile().getParent())) {
					// reset(); already done in finally block
					return Reply.RESPONSE_530_ACCESS_DENIED;
				}

                break;

            default:
            	// reset(); already done in finally block
                throw UnhandledCommandException.create(DataConnectionHandler.class,
                    request);
            }*/

/*            //check credits
            if (isRetr) {
                if ((conn.getUserNull().getKeyedMap().getObjectFloat(
                        UserManagement.RATIO) != 0)
                        && (conn.getGlobalContext().getConfig()
                                .getCreditLossRatio(_transferFile,
                                        conn.getUserNull()) != 0)
                        && (conn.getUserNull().getCredits() < _transferFile
                                .length())) {
                	// reset(); already done in finally block
                    return new Reply(550, "Not enough credits.");
                }
            }*/

            //setup _rslave
            //if (isCpsv)
            if (ts.isPASVDownload()) {
                //check pretransfer
                try {
					if (!ts.getTransferFile().getSlaves().contains(ts.getTransferSlave())) {
						// reset(); already done in finally block
						response = StandardCommandManager.genericResponse("RESPONSE_503_BAD_SEQUENCE_OF_COMMANDS");
		    			doPostHooks(request, response);
		    	        return response;
					}
				} catch (FileNotFoundException e) {
					// reset(); already done in finally block
					response = StandardCommandManager.genericResponse("RESPONSE_550_REQUESTED_ACTION_NOT_TAKEN");
	    			doPostHooks(request, response);
	    	        return response;
				}
				// reset(); already done in finally block
            } else if (ts.isPASVUpload()) {
            	// do nothing at this point
            } else if (!ts.isPreTransfer()) { // && ts.isPort()
            	// is a PORT command with no previous PRET command
                try {
                    if (direction == Transfer.TRANSFER_SENDING_DOWNLOAD) {
							ts.setTransferSlave(conn.getGlobalContext()
								.getSlaveSelectionManager().getASlave(
										conn.getGlobalContext()
												.getSlaveManager()
												.getAvailableSlaves(),
										Transfer.TRANSFER_SENDING_DOWNLOAD,
										conn, ts.getTransferFile()));
					} else if (direction == Transfer.TRANSFER_RECEIVING_UPLOAD) {
						ts.setTransferSlave(conn.getGlobalContext()
								.getSlaveSelectionManager().getASlave(
										conn.getGlobalContext()
												.getSlaveManager()
												.getAvailableSlaves(),
										Transfer.TRANSFER_RECEIVING_UPLOAD,
										conn, ts.getTransferFile()));
					} else {
						// reset(); already done in finally block
						throw new RuntimeException();
					}
                } catch (NoAvailableSlaveException ex) {
                	// TODO Might not be good to 450 reply always
                	// from rfc: 450 Requested file action not taken. File
					// unavailable (e.g., file busy).
                	// reset(); already done in finally block
                	response = StandardCommandManager.genericResponse("RESPONSE_450_SLAVE_UNAVAILABLE");
	    			doPostHooks(request, response);
	    	        return response;
                }
            } else { // ts.isPreTransfer() && ts.isPort()
            	// they issued PRET before PORT
            	// let's honor that SlaveSelection
            	// ts.setTransferSlave() was already called in PRET
            }

            if (isStor) {
                //setup upload
                FileHandle fh = ts.getTransferFile();
                // cannot use this FileHandle for anything but name, parent, and path
                // it doesn't exist in the VFS yet!
                try {
                	if (ts.isPasv()) {
                		ts.setTransferFile(fh.getParent().createFile(fh.getName(),
							conn.getUserNull().getName(),
							conn.getUserNull().getGroup(),
							ts.getTransferSlave()));
                	} else { // ts.isPort()
                		try {
            				fh = conn.getCurrentDirectory().getFile(conn.getRequest().getArgument());
            			} catch (FileNotFoundException e) {
            				// this is good, do nothing
            				// should be null already, but just for my (current) sanity
            				fh = null;
            			} catch (ObjectNotValidException e) {
            				// this is not good, file exists
                        	// until we can upload multiple instances of files
            				response = StandardCommandManager.genericResponse(
        	    					"RESPONSE_553_REQUESTED_ACTION_NOT_TAKEN_FILE_EXISTS");
        	    			doPostHooks(request, response);
        	    	        return response;
            			}
            			if (fh != null) {
            				// this is not good, file exists
                        	// until we can upload multiple instances of files
            				response = StandardCommandManager.genericResponse(
        	    					"RESPONSE_553_REQUESTED_ACTION_NOT_TAKEN_FILE_EXISTS");
        	    			doPostHooks(request, response);
        	    	        return response;
            			}
            			fh = conn.getCurrentDirectory().getNonExistentFileHandle(conn.getRequest().getArgument());

                        if (!ListUtils.isLegalFileName(fh.getName())) {
                        	reset(conn);
                        	response = StandardCommandManager.genericResponse(
        	    					"RESPONSE_553_REQUESTED_ACTION_NOT_TAKEN");
        	    			doPostHooks(request, response);
        	    			return response;
                        }
    					ts.setTransferFile(fh.getParent().createFile(
								fh.getName(), conn.getUserNull().getName(),
								conn.getUserNull().getGroup(),
								ts.getTransferSlave()));
                	}
				} catch (FileExistsException e) {
					// reset is handled in finally
					response = StandardCommandManager.genericResponse(
	    					"RESPONSE_553_REQUESTED_ACTION_NOT_TAKEN_FILE_EXISTS");
	    			doPostHooks(request, response);
	    	        return response;
				} catch (FileNotFoundException e) {
					// reset is handled in finally
					logger.debug("",e);
					response = StandardCommandManager.genericResponse(
	    					"RESPONSE_550_REQUESTED_ACTION_NOT_TAKEN");
	    			doPostHooks(request, response);
	    			return response;
				}
            }

            // setup _transfer

            if (ts.isPort()) {
                    String index;
					try {
						index = ts.getTransferSlave().issueConnectToSlave(ts.getPortAddress()
								.getAddress().getHostAddress(), ts.getPortAddress()
								.getPort(), ts.getSendFilesEncrypted(), ts.getSSLHandshakeClientMode());
	                    ConnectInfo ci = ts.getTransferSlave().fetchTransferResponseFromIndex(index);
	                   	ts.setTransfer(ts.getTransferSlave().getTransfer(ci.getTransferIndex()));
					} catch (SlaveUnavailableException e) {
						response = StandardCommandManager.genericResponse("RESPONSE_450_SLAVE_UNAVAILABLE");
		    			doPostHooks(request, response);
		    	        return response;
					} catch (RemoteIOException e) {
						// couldn't talk to the slave, this is bad
						ts.getTransferSlave().setOffline(e);
						response = StandardCommandManager.genericResponse("RESPONSE_450_SLAVE_UNAVAILABLE");
		    			doPostHooks(request, response);
		    	        return response;
					}
            } else if (ts.isPASVDownload() || ts.isPASVUpload()) {
                //_transfer is already set up by doPASV()
            } else {
            	// reset(); already done in finally block
            	response = StandardCommandManager.genericResponse("RESPONSE_503_BAD_SEQUENCE_OF_COMMANDS");
    			doPostHooks(request, response);
    	        return response;
            }

            {
                PrintWriter out = conn.getControlWriter();
                out.write(new FtpReply(150,
                        "File status okay; about to open data connection " +
                        (isRetr ? "from " : "to ") + ts.getTransferSlave().getName() + ".").toString());
                out.flush();
            }

            TransferStatus status = null;

            //transfer
            try {
                //TODO ABORtable transfers
                if (isRetr) {
                    ts.getTransfer().sendFile(ts.getTransferFile().getPath(), ts.getType(),
                        ts.getResumePosition());

                    while (true) {
                        status = ts.getTransfer().getTransferStatus();

                        if (status.isFinished()) {
                            break;
                        }

                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e1) {
                        }
                    }
                } else if (isStor) {
                    ts.getTransfer().receiveFile(ts.getTransferFile().getPath(), ts.getType(),
                        ts.getResumePosition());

                    while (true) {
                        status = ts.getTransfer().getTransferStatus();
                        ts.getTransferFile().setSize(status.getTransfered());
                        if (status.isFinished()) {
                            break;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e1) {
                        }
                    }
                } else {
                    throw new RuntimeException();
                }
            } catch (IOException ex) {
            	logger.debug("", ex);
                if (ex instanceof TransferFailedException) {
                	// the below chunk makes no sense, we don't process it anywhere
/*                    status = ((TransferFailedException) ex).getStatus();
                    conn.getGlobalContext()
                        .dispatchFtpEvent(new TransferEvent(conn, eventType,
                            _transferFile, conn.getClientAddress(), _rslave,
                            _transfer.getAddress().getAddress(), _type, false));
*/
                    if (isRetr) {
                        conn.getUserNull().updateCredits(-status.getTransfered());
                    }
                }

                if (isStor) {
                    try {
						ts.getTransferFile().delete();
					} catch (FileNotFoundException e) {
						// ahh, great! :)
					}
                    logger.error("IOException during transfer, deleting file",
                        ex);
                    response = new CommandResponse(426, "Transfer failed, deleting file");
                } else {
                    logger.error("IOException during transfer", ex);
                    response = new CommandResponse(426, ex.getMessage());
                }
                
                response.addComment(ex.getMessage());
            	// reset(); already done in finally block
                doPostHooks(request, response);
                return response;
            } catch (SlaveUnavailableException e) {
            	logger.debug("", e);

                if (isStor) {
                    try {
						ts.getTransferFile().delete();
					} catch (FileNotFoundException e1) {
						// ahh, great! :)
					}
                    logger.error("Slave went offline during transfer, deleting file",
                        e);
                    response = new CommandResponse(426,
                            "Slave went offline during transfer, deleting file");
                } else {
                    logger.error("Slave went offline during transfer", e);
                    response = new CommandResponse(426,
                            "Slave went offline during transfer");
                }

                response.addComment(e.getLocalizedMessage());
            	// reset(); already done in finally block
                doPostHooks(request, response);
                return response;
            }

            //		TransferThread transferThread = new TransferThread(rslave,
            // transfer);
            //		System.err.println("Calling interruptibleSleepUntilFinished");
            //		try {
            //			transferThread.interruptibleSleepUntilFinished();
            //		} catch (Throwable e1) {
            //			e1.printStackTrace();
            //		}
            //		System.err.println("Finished");
            env = new ReplacerEnvironment();
            env.add("bytes", Bytes.formatBytes(status.getTransfered()));
            env.add("speed", Bytes.formatBytes(status.getXferSpeed()) + "/s");
            env.add("seconds", "" + ((float)status.getElapsed() / 1000F));
            env.add("checksum", Checksum.formatChecksum(status.getChecksum()));

            response = new CommandResponse(226, jprintf(_bundle,
                    "transfer.complete", env, request.getUser()));

            synchronized (conn.getGlobalContext()) { // need to synchronize
														// here so only one
				// TransferEvent can be sent at a time
            	if (isStor) {
            		try {
            			if (ts.getResumePosition() == 0) {
            				ts.getTransferFile().setCheckSum(status.getChecksum());
            			} // else, fetch checksum from slave when resumable uploads are implemented.
            			
            			ts.getTransferFile().setSize(status.getTransfered());
            			ts.getTransferFile().setLastModified(System.currentTimeMillis());
            			ts.getTransferFile().setXfertime(ts.getTransfer().getElapsed());
            		} catch (FileNotFoundException e) {
            			// this is kindof odd
            			// it was a successful transfer, yet the file is gone
            			// lets just return the response
            			doPostHooks(request, response);
                        return response;					
            		}
            	}

/*				boolean zipscript = zipscript(isRetr, isStor, status
						.getChecksum(), response, targetFileName, targetDir);*/

					// transferstatistics
					if (isRetr) {

						float ratio = conn.getGlobalContext().getConfig()
								.getCreditLossRatio(ts.getTransferFile().getParent(),
										conn.getUserNull());

						if (ratio != 0) {
							conn.getUserNull().updateCredits(
									(long) (-status.getTransfered() * ratio));
						}

						if (!conn.getGlobalContext().getConfig()
								.checkPathPermission("nostatsdn",
										conn.getUserNull(),
										conn.getCurrentDirectory())) {
							conn.getUserNull().updateDownloadedBytes(
									status.getTransfered());
							conn.getUserNull().updateDownloadedTime(
									status.getElapsed());
							conn.getUserNull().updateDownloadedFiles(1);
						}
					} else {

						conn.getUserNull().updateCredits(
								(long) (status.getTransfered() * conn
										.getGlobalContext().getConfig()
										.getCreditCheckRatio(ts.getTransferFile().getParent(),
												conn.getUserNull())));
						if (!conn.getGlobalContext().getConfig()
								.checkPathPermission("nostatsup",
										conn.getUserNull(),
										conn.getCurrentDirectory())) {
							conn.getUserNull().updateUploadedBytes(
									status.getTransfered());
							conn.getUserNull().updateUploadedTime(
									status.getElapsed());
							conn.getUserNull().updateUploadedFiles(1);
						}
					}

					try {
						conn.getUserNull().commit();
					} catch (UserFileException e) {
						logger.warn("", e);
					}
				}

            // Dispatch for both STOR and RETR
				conn.getGlobalContext().dispatchFtpEvent(
						new TransferEvent(conn, eventType, ts.getTransferFile(), conn
								.getClientAddress(), ts.getTransferSlave(), ts.getTransfer()
								.getAddress().getAddress(), ts.getType()));
				doPostHooks(request, response);
                return response;
        } finally {
            reset(conn);
        }
    }

    public void unload() {
    }

/*    *//**
	 * @param isRetr
	 * @param isStor
	 * @param status
	 * @param response
	 * @param targetFileName
	 * @param targetDir
	 *            Returns true if crc check was okay, i.e, if credits should be
	 *            altered
	 *//*
    private boolean zipscript(boolean isRetr, boolean isStor, long checksum,
        Reply response, String targetFileName,
        LinkedRemoteFileInterface targetDir) {
        // zipscript
        logger.debug("Running zipscript on file " + targetFileName +
            " with CRC of " + checksum);

        if (isRetr) {
            //compare checksum from transfer to cached checksum
            logger.debug("checksum from transfer = " + checksum);

            if (checksum != 0) {
                response.addComment("Checksum from transfer: " +
                    Checksum.formatChecksum(checksum));

                long cachedChecksum;
                cachedChecksum = _transferFile.getCheckSumCached();

                if (cachedChecksum == 0) {
                    _transferFile.setCheckSum(checksum);
                } else if (cachedChecksum != checksum) {
                    response.addComment(
                        "WARNING: checksum from transfer didn't match cached checksum");
                    logger.info("checksum from transfer " +
                        Checksum.formatChecksum(checksum) +
                        "didn't match cached checksum" +
                        Checksum.formatChecksum(cachedChecksum) + " for " +
                        _transferFile.toString() + " from slave " +
                        _rslave.getName(), new Throwable());
                }

                //compare checksum from transfer to checksum from sfv
                try {
                    long sfvChecksum = _transferFile.getParentFileNull()
                                                    .lookupSFVFile()
                                                    .getChecksum(_transferFile.getName());

                    if (sfvChecksum == checksum) {
                        response.addComment(
                            "checksum from transfer matched checksum in .sfv");
                    } else {
                        response.addComment(
                            "WARNING: checksum from transfer didn't match checksum in .sfv");
                    }
                } catch (NoAvailableSlaveException e1) {
                    response.addComment(
                        "slave with .sfv offline, checksum not verified");
                } catch (FileNotFoundException e1) {
                    //continue without verification
                } catch (NoSFVEntryException e1) {
                    //file not found in .sfv, continue
                } catch (IOException e1) {
                    logger.info("", e1);
                    response.addComment("IO Error reading sfv file: " +
                        e1.getMessage());
                }
            } else { // slave has disabled download crc

                //response.addComment("Slave has disabled download checksum");
            }
        } else if (isStor) {
            if (!targetFileName.toLowerCase().endsWith(".sfv")) {
                try {
                	long sfvChecksum = targetDir.lookupSFVFile().getChecksum(targetFileName);
                     If no exceptions are thrown means that the sfv is avaible and has a entry
                     * for that file.
                     * With this certain, we can assume that files that have CRC32 = 0 either is a
                     * 0byte file (bug!) or checksummed transfers are disabled(size is different
                     * from 0bytes though).                 
                     

                    if (checksum == sfvChecksum) {
                    	// Good! transfer checksum matches sfv checksum
                        response.addComment("checksum match: SLAVE/SFV:" +
                            Long.toHexString(checksum));
                    } else if (checksum == 0) {
                    	// Here we have to conditions:
                    	if (_transferFile.length() == 0) {
                    		// The file has checksum = 0 and the size = 0 
                    		// then it should be deleted.
                    		response.addComment("0Byte File, Deleting...");
                    		_transferFile.delete();
                    		return false;
                    	} else
                    		// The file has checksum = 0, although the size is != 0,
                    		// meaning that we are not using checked transfers.
                    		response.addComment("checksum match: SLAVE/SFV: DISABLED");
                    } else {
                        response.addComment("checksum mismatch: SLAVE: " +
                            Long.toHexString(checksum) + " SFV: " +
                            Long.toHexString(sfvChecksum));
                        response.addComment(" deleting file");
                        response.setMessage("Checksum mismatch, deleting file");
                        _transferFile.delete();

                        //				getUser().updateCredits(
                        //					- ((long) getUser().getRatio() * transferedBytes));
                        //				getUser().updateUploadedBytes(-transferedBytes);
                        // response.addComment(conn.status());
                        return false; // don't modify credits

                        //				String badtargetFilename = targetFilename + ".bad";
                        //
                        //				try {
                        //					LinkedRemoteFile badtargetFile =
                        //						targetDir.getFile(badtargetFilename);
                        //					badtargetFile.delete();
                        //					response.addComment(
                        //						"zipscript - removing "
                        //							+ badtargetFilename
                        //							+ " to be replaced with new file");
                        //				} catch (FileNotFoundException e2) {
                        //					//good, continue...
                        //					response.addComment(
                        //						"zipscript - checksum mismatch, renaming to "
                        //							+ badtargetFilename);
                        //				}
                        //				targetFile.renameTo(targetDir.getPath() +
                        // badtargetFilename);
                    }
                } catch (NoAvailableSlaveException e) {
                    response.addComment(
                        "zipscript - SFV unavailable, slave(s) with .sfv file is offline");
                } catch (NoSFVEntryException e) {
                    response.addComment("zipscript - no entry in sfv for file");
                } catch (IOException e) {
                    response.addComment(
                        "zipscript - SFV unavailable, IO error: " +
                        e.getMessage());
                }
            }
        }

        return true; // modify credits, transfer was okay
    }
*/
/*	public synchronized void handshakeCompleted(HandshakeCompletedEvent arg0) {
		_handshakeCompleted = true;
		notifyAll();
	}*/
}
