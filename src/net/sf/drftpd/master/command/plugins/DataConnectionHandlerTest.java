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
package net.sf.drftpd.master.command.plugins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;

import junit.framework.TestCase;
import junit.framework.TestSuite;
import net.sf.drftpd.master.ConnectionManager;
import net.sf.drftpd.master.FtpReply;
import net.sf.drftpd.master.FtpRequest;
import net.sf.drftpd.master.config.FtpConfig;

import org.apache.log4j.BasicConfigurator;
import org.drftpd.commands.UnhandledCommandException;
import org.drftpd.tests.DummyBaseFtpConnection;

/**
 * @author mog
 * @version $Id: DataConnectionHandlerTest.java,v 1.6.2.1 2004/06/19 23:37:26 mog Exp $
 */
public class DataConnectionHandlerTest extends TestCase {

	private static class FC extends FtpConfig {
		public FC() {
			super();
			try {
				loadConfig(new Properties(), null);
			} catch (IOException e) {
				throw new RuntimeException();
			}
		}
	}
	public static TestSuite suite() {
		return new TestSuite(DataConnectionHandlerTest.class);
	}
	DummyBaseFtpConnection conn;
	DataConnectionHandler dch;
	public DataConnectionHandlerTest(String fName) {
		super(fName);
	}

	public void assertBetween(int val, int from, int to) {
		assertTrue(val + " is less than " + from, val >= from);
		assertTrue(val + " is more than " + from, val <= to);
	}
	private String list() {
		LIST list = (LIST) new LIST().initialize(null, null);

		ByteArrayOutputStream out =
			conn.getDummySSF().getDummySocket().getByteArrayOutputStream();

		conn.setRequest(new FtpRequest("LIST"));
		FtpReply reply = list.execute(conn);
		String replystr = conn.getDummyOut().getBuffer().toString();
		assertEquals(150, Integer.parseInt(replystr.substring(0, 3)));
		assertEquals(226, reply.getCode());

		String ret = out.toString();
		out.reset();
		return ret;
	}

	private String pasvList() throws UnhandledCommandException {
		conn.setRequest(new FtpRequest("PRET LIST"));

		FtpReply reply;
		reply = dch.execute(conn);
		assertEquals(reply.toString(), 200, reply.getCode());

		conn.setRequest(new FtpRequest("PASV"));
		reply = dch.execute(conn);
		assertEquals(reply.toString(), 227, reply.getCode());

		return list();
	}

	private String portList() throws UnhandledCommandException {
		conn.setRequest(new FtpRequest("PORT 127,0,0,1,0,0"));
		dch.execute(conn);

		return list();
	}

	protected void setUp() throws Exception {
		BasicConfigurator.configure();
		dch =
			(DataConnectionHandler) new DataConnectionHandler().initialize(
				null,
				null);
		conn = new DummyBaseFtpConnection(dch) {
			public FtpConfig getConfig() {
				return new FC();
			}
		};
	}

	protected void tearDown() throws Exception {
		dch = null;
	}

	public void testMixedListEqual() throws UnhandledCommandException {
		String list = portList();
		assertEquals(list, pasvList());
		assertEquals(list, portList());
		assertEquals(list, pasvList());
		testPASVWithoutPRET();
		assertEquals(list, portList());
		testPASVWithoutPRET();
		assertEquals(list, portList());
	}
	public void testPasvList() throws UnhandledCommandException {
		pasvList();
	}

	public void testPASVWithoutPRET() throws UnhandledCommandException {
		conn.setRequest(new FtpRequest("PASV"));
		FtpReply reply = dch.execute(conn);
		assertBetween(reply.getCode(), 500, 599);
	}

	public void testPortList() throws UnhandledCommandException {
		portList();
	}
}
