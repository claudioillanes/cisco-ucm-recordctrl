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
 * PHONE RECORDING SERVLET
 * 
 * Provides a simple microservice approach to controlling recording at a CUCM phone.
 * Its primary use is to allow a CCE Finesse desktop agent to trigger recording via
 * a workflow but can equally well be used by any client that can issue an HTTP PUT
 * request.  All phones for which recording control is required should be assigned to
 * the JTAPI user.
 *
 * HTTP PUT request URL:
 *      http://<host:port/path>/recording/<extensionID>
 *
 * Request JSON body items:
 *      action      START or STOP
 *      tone        LOCAL, REMOTE, BOTH or NONE
 *      notify      true or false
 *      
 * Servlet initialisation parameters:
 *      CUCMHost            CUCM hostname or IP address
 *      CUCMJTapiUser       CUCM application JTAPI user
 *      CUCMJTapiPassword   CUCM application JTAPI password
 *      PlayTone            Default setting for recording tone
 *      PhoneNotification   Default setting for recording notification at phone
 *
 * -----------------------------------------------------------------------------------
 * 1.0,  Paul Tindall, Cisco, 10 Apr 2017 Initial version, for PoC, not hardened
 * -----------------------------------------------------------------------------------
 */

import com.cisco.cti.util.Condition;
import com.cisco.jtapi.extensions.CiscoCall;
import com.cisco.jtapi.extensions.CiscoTerminalConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.telephony.Address;
import javax.telephony.AddressObserver;
import javax.telephony.CallObserver;
import javax.telephony.Connection;
import javax.telephony.InvalidArgumentException;
import javax.telephony.InvalidStateException;
import javax.telephony.JtapiPeer;
import javax.telephony.JtapiPeerFactory;
import javax.telephony.JtapiPeerUnavailableException;
import javax.telephony.MethodNotSupportedException;
import javax.telephony.PrivilegeViolationException;
import javax.telephony.Provider;
import javax.telephony.ProviderObserver;
import javax.telephony.ResourceUnavailableException;
import javax.telephony.TerminalConnection;
import javax.telephony.events.AddrEv;
import javax.telephony.events.CallEv;
import javax.telephony.events.ProvEv;
import javax.telephony.events.ProvInServiceEv;
import org.json.JSONObject;

@WebServlet(name = "Recording",
            urlPatterns = {"/recording/*"},
            loadOnStartup = 1,
            initParams =
            {
                @WebInitParam(name = "CUCMHost", value = "your-cucm"),
                @WebInitParam(name = "CUCMJTapiUser", value = "your-jtapi-user"),
                @WebInitParam(name = "CUCMJTapiPassword", value = "your-jtapi-password"),
//              @WebInitParam(name = "PlayTone", value = "LOCAL"),
//              @WebInitParam(name = "PhoneNotification", value = "true")
            })

public class Recording extends HttpServlet {
    
    static Provider prov;
    static String default_play_tone = "NONE";
    static boolean default_phone_notification = false;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        String ucm = getInitParameter("CUCMHost");
        String usr = getInitParameter("CUCMJTapiUser");
        String pwd = getInitParameter("CUCMJTapiPassword");

        String initp;
        
        if ((initp = getInitParameter("PlayTone")) != null) default_play_tone = initp;
        if ((initp = getInitParameter("PhoneNotification")) != null) default_phone_notification = Boolean.parseBoolean(initp);

        final Condition inService = new Condition();

        try {
            JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);

            String providerString = ucm + ";login=" + usr + ";passwd=" + pwd;;
            System.out.println("Opening " + providerString + "...\n");
            prov = peer.getProvider(providerString);
            prov.addObserver(new ProviderObserver() {
                @Override
                public void providerChangedEvent(ProvEv[] provevs) {
                    if (provevs != null) {
                        for (ProvEv provev : provevs) {
                            if (provev instanceof ProvInServiceEv) {
                                inService.set();
                            }
                        }
                    }
                }
            });
            inService.waitTrue();

            Address[] addresses = prov.getAddresses();
            System.out.println("Address Count = " + addresses.length);
            for (Address a : addresses) {
                System.out.println(a.getName());

                a.addObserver(new AddressObserver() {
                    @Override
                    public void addressChangedEvent(AddrEv[] addrevs) {
                        if (addrevs != null) {
                            for (AddrEv adev : addrevs) {
                                System.out.println("Address " + adev.getAddress().getName() + ", Event = " + adev.toString());
                            }
                        }                        
                    }
                });
                
                a.addCallObserver(new CallObserver() {
                    @Override
                    public void callChangedEvent(CallEv[] callevs) {
                        if (callevs != null) {
                            for (CallEv cev : callevs) {
                                System.out.println("Call " + cev.getCall().getConnections()[0].toString() + ", Event = " + cev.toString());
                            }
                        }                        
                    }
                });
            }

        } catch (JtapiPeerUnavailableException | ResourceUnavailableException | MethodNotSupportedException ex) {
            Logger.getLogger(Recording.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        
        try {
            String[] pathitems;

            if (req.getPathInfo() == null || (pathitems = req.getPathInfo().split("/")).length < 2) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid URL path, missing mandatory fields");                

            } else {
                Address a = prov.getAddress(pathitems[1]);
                Connection[] cn = a.getConnections();

                if (cn == null) {
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Extension " + a.getName() + " has no calls");                
                    
                } else {
                    TerminalConnection tcn = cn[0].getTerminalConnections()[0];
                    System.out.println("Connection State = " + tcn.toString());

                    BufferedReader streamReader = new BufferedReader(new InputStreamReader(req.getInputStream()));
                    StringBuilder reqcontent = new StringBuilder();
                    String inputStr;

                    while ((inputStr = streamReader.readLine()) != null) {
                        reqcontent.append(inputStr);
                    }

                    JSONObject recmsg = new JSONObject(reqcontent.toString());
                    System.out.println(recmsg.toString(4));
                    String action = recmsg.getString("action");

                    String tone = recmsg.optString("tone", default_play_tone).toUpperCase();
                    int playtone = "LOCAL".equals(tone)  ? CiscoCall.PLAYTONE_LOCALONLY :
                                   "REMOTE".equals(tone) ? CiscoCall.PLAYTONE_REMOTEONLY :
                                   "BOTH".equals(tone)   ? CiscoCall.PLAYTONE_BOTHLOCALANDREMOTE :
                                   "NONE".equals(tone)   ? CiscoCall.PLAYTONE_NOLOCAL_OR_REMOTE : CiscoCall.PLAYTONE_NOLOCAL_OR_REMOTE;

                    boolean notify = recmsg.optBoolean("notify", default_phone_notification);
                    int phonenotify = notify ? CiscoTerminalConnection.RECORDING_INVOCATION_TYPE_USER : CiscoTerminalConnection.RECORDING_INVOCATION_TYPE_SILENT;

                    resp.setStatus(HttpServletResponse.SC_ACCEPTED);

                    switch(action.toUpperCase()) {
                        case "START":
                            ((CiscoTerminalConnection)tcn).startRecording(playtone, phonenotify);
                            break;

                        case "STOP":
                            ((CiscoTerminalConnection)tcn).stopRecording(phonenotify);
                            break;

                        default:
                            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid recording action /" + action + "/");
                    }
                }
            }
                    
        } catch (InvalidArgumentException | InvalidStateException | PrivilegeViolationException | ResourceUnavailableException ex) {
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
