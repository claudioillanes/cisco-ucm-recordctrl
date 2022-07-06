package com.cisco.pt.cucmctrl;

/*
 * ===================================================================================
 * IMPORTANT
 *
 * This sample is intended for distribution on Cisco DevNet. It does not form part of
 * the product release software and is not Cisco TAC supported. You should refer
 * to the Cisco DevNet website for the support rules that apply to samples published
 * for download.
 * ===================================================================================
 *
 * PHONE MONITORING SERVLET
 * 
 * Provides a simple microservice approach to controlling monitoring of a CUCM phone.
 * Can be used by Finesse gadgets, CVP applications or any client that can issue an
 * HTTP PUT request.  All phones that will be monitors or targets should be assigned
 * to the JTAPI user configured on the Recording servlet as both servlets share the
 * same CUCM control.
 *
 * HTTP PUT request URL:
 *      http://<host:port/path>/monitor/<targetExtensionID>
 *
 * Request JSON body items:
 *      action      START
 *      tone        LOCAL, REMOTE, BOTH or NONE
 *      type        SILENT or WHISPER
 *      
 * Servlet initialisation parameters:
 *      PlayTone      Default setting for monitoring tone
 *      MonitorType   Default setting for monitor type
 *
 * -----------------------------------------------------------------------------------
 * 1.0,  Paul Tindall, Cisco, 10 Apr 2017 Initial version, for PoC, not hardened
 * -----------------------------------------------------------------------------------
 */

import com.cisco.jtapi.extensions.CiscoCall;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.telephony.Address;
import javax.telephony.Call;
import javax.telephony.Connection;
import javax.telephony.InvalidArgumentException;
import javax.telephony.InvalidPartyException;
import javax.telephony.InvalidStateException;
import javax.telephony.MethodNotSupportedException;
import javax.telephony.PrivilegeViolationException;
import javax.telephony.ResourceUnavailableException;
import javax.telephony.Terminal;
import javax.telephony.TerminalConnection;
import org.json.JSONObject;

@WebServlet(name = "Monitoring",
            urlPatterns = {"/monitor/*"},
            initParams =
            {
//              @WebInitParam(name = "PlayTone", value = "LOCAL"),
//              @WebInitParam(name = "MonitorType", value = "SILENT")
            })

public class Monitor extends HttpServlet {

    static String default_play_tone = "NONE";
    static String default_monitor_type = "SILENT";


    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        String initp;
        
        if ((initp = getInitParameter("PlayTone")) != null) default_play_tone = initp;
        if ((initp = getInitParameter("MonitorType")) != null) default_monitor_type = initp;
    }


    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        
        try {
            String[] pathitems;

            if (req.getPathInfo() == null || (pathitems = req.getPathInfo().split("/")).length < 2) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid URL path, missing mandatory fields");                

            } else {
                Address target = Recording.prov.getAddress(pathitems[1]);
                Connection[] cn = target.getConnections();

                if (cn == null) {
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Extension " + target.getName() + " has no calls");                
                    
                } else {
                    TerminalConnection tcn = cn[0].getTerminalConnections()[0];
                    System.out.println("Connection State = " + tcn.toString());

                    BufferedReader streamReader = new BufferedReader(new InputStreamReader(req.getInputStream()));
                    StringBuilder reqcontent = new StringBuilder();
                    String inputStr;

                    while ((inputStr = streamReader.readLine()) != null) {
                        reqcontent.append(inputStr);
                    }

                    JSONObject mtrmsg = new JSONObject(reqcontent.toString());
                    System.out.println(mtrmsg.toString(4));
                    String action = mtrmsg.getString("action");
                    String monidn = mtrmsg.getString("monitor");

                    String tone = mtrmsg.optString("tone", default_play_tone).toUpperCase();
                    int playtone = "LOCAL".equals(tone)  ? CiscoCall.PLAYTONE_LOCALONLY :
                                   "REMOTE".equals(tone) ? CiscoCall.PLAYTONE_REMOTEONLY :
                                   "BOTH".equals(tone)   ? CiscoCall.PLAYTONE_BOTHLOCALANDREMOTE :
                                   "NONE".equals(tone)   ? CiscoCall.PLAYTONE_NOLOCAL_OR_REMOTE : CiscoCall.PLAYTONE_NOLOCAL_OR_REMOTE;

                    String type = mtrmsg.optString("type", default_monitor_type).toUpperCase();
                    int monitype = "SILENT".equals(type)  ? CiscoCall.SILENT_MONITOR :
                                   "WHISPER".equals(type) ? CiscoCall.WHISPER_MONITOR : CiscoCall.SILENT_MONITOR;

                    resp.setStatus(HttpServletResponse.SC_ACCEPTED);

                    switch(action.toUpperCase()) {
                        case "START":
                            Call monicall = Recording.prov.createCall();
                            Address moniaddr = Recording.prov.getAddress(monidn);
                            Terminal moniterm = moniaddr.getTerminals()[0];
                            ((CiscoCall)monicall).startMonitor(moniterm, moniaddr, tcn, monitype, playtone);
                            break;

                        case "STOP":
                        default:
                            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid monitoring action /" + action + "/");
                    }
                }
            }
                    
        } catch (InvalidPartyException | MethodNotSupportedException | InvalidArgumentException | InvalidStateException | PrivilegeViolationException | ResourceUnavailableException ex) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
        }
    }


    
// <editor-fold defaultstate="collapsed" desc="HttpServlet methods. Click on the + sign on the left to edit the code.">
    /**
     * Returns a short description of the servlet.
     *
     * @return a String containing servlet description
     */
    @Override
        public String getServletInfo() {
        return "Provides simple web interface onto recording controls";
    }// </editor-fold>

}
