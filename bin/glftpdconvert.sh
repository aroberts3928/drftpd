#!/bin/sh
#Set this to the root of your glftpd install and
# GlftpdUserManager will look for ftp-data/users/* and etc/passwd in this directory.
export VMARGS="-Dglftpd.root=/glftpd"

# Or you can specify the files yourself if your userdir and passwd file are in
# non-standard places. these override the glftpd.root setting
export VMARGS="
	-Dglftpd.users=/glftpd/ftp-data/users \
	-Dglftpd.passwd=/glftpd/etc/passwd"

export CLASSPATH="classes:lib/jdom.jar:lib/martyr.jar:lib/oro.jar:lib/JSX1.0.7.4.jar:lib/replacer.jar:lib/log4j-1.2.8.jar"

### END CONFIG ###

exec java ${VMARGS} $@ net.sf.drftpd.master.usermanager.UserManagerConverter net.sf.drftpd.master.usermanager.glftpd.GlftpdUserManager net.sf.drftpd.master.usermanager.jsx.JSXUserManager
