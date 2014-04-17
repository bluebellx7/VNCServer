VNCServer
=========



PREREQUISITS:
============
apache ant already installed



BUILDING SOURCE:
===============
0. set the debug opt accordingly in:
   com.jcope.debug.Debug.java
   // set to false to make faster/not printout crap
   public static final boolean DEBUG = Boolean.TRUE;
1. run ant in the workspace dir:
   ant



CONFIGURE SERVER:
================
**************************************
* The default run mode is fully open *
*  you should really set a password  *
**************************************
(windows): service setup
  (other): sh service.sh setup



RUN VNC SERVER:
==============
*************************************
* note there is a "restart" command *
*************************************
(windows): service start
  (other): sh service.sh start



STOP VNC SERVER:
===============
(windows): service stop
  (other): sh service.sh stop



RUN VNC CLIENT:
==============
(windows): client
  (other): sh client.sh



OTHER ANT BUILD TARGETS:
===============================
(See build.xml for more detail)
-------------------------------
ant clean
ant cleangit


