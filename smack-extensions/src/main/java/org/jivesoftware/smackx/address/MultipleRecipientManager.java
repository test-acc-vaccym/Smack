/**
 *
 * Copyright 2003-2006 Jive Software.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.smackx.address;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.FeatureNotSupportedException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.util.Cache;
import org.jivesoftware.smackx.address.packet.MultipleAddresses;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.disco.packet.DiscoverItems;
import org.jxmpp.util.XmppStringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A MultipleRecipientManager allows to send packets to multiple recipients by making use of
 * <a href="http://www.jabber.org/jeps/jep-0033.html">JEP-33: Extended Stanza Addressing</a>.
 * It also allows to send replies to packets that were sent to multiple recipients.
 *
 * @author Gaston Dombiak
 */
public class MultipleRecipientManager {

    private static final Logger LOGGER = Logger.getLogger(MultipleRecipientManager.class.getName());

    /**
     * Create a cache to hold the 100 most recently accessed elements for a period of
     * 24 hours.
     */
    private static Cache<String, String> services = new Cache<String, String>(100, 24 * 60 * 60 * 1000);

    /**
     * Sends the specified packet to the list of specified recipients using the
     * specified connection. If the server has support for JEP-33 then only one
     * packet is going to be sent to the server with the multiple recipient instructions.
     * However, if JEP-33 is not supported by the server then the client is going to send
     * the packet to each recipient.
     *
     * @param connection the connection to use to send the packet.
     * @param packet     the packet to send to the list of recipients.
     * @param to         the list of JIDs to include in the TO list or <tt>null</tt> if no TO
     *                   list exists.
     * @param cc         the list of JIDs to include in the CC list or <tt>null</tt> if no CC
     *                   list exists.
     * @param bcc        the list of JIDs to include in the BCC list or <tt>null</tt> if no BCC
     *                   list exists.
     * @throws FeatureNotSupportedException if special XEP-33 features where requested, but the
     *         server does not support them.
     * @throws XMPPErrorException if server does not support JEP-33: Extended Stanza Addressing and
     *                       some JEP-33 specific features were requested.
     * @throws NoResponseException if there was no response from the server.
     * @throws NotConnectedException 
     */
    public static void send(XMPPConnection connection, Packet packet, List<String> to, List<String> cc, List<String> bcc) throws NoResponseException, XMPPErrorException, FeatureNotSupportedException, NotConnectedException
   {
        send(connection, packet, to, cc, bcc, null, null, false);
    }

    /**
     * Sends the specified packet to the list of specified recipients using the specified
     * connection. If the server has support for JEP-33 then only one packet is going to be sent to
     * the server with the multiple recipient instructions. However, if JEP-33 is not supported by
     * the server then the client is going to send the packet to each recipient.
     * 
     * @param connection the connection to use to send the packet.
     * @param packet the packet to send to the list of recipients.
     * @param to the list of JIDs to include in the TO list or <tt>null</tt> if no TO list exists.
     * @param cc the list of JIDs to include in the CC list or <tt>null</tt> if no CC list exists.
     * @param bcc the list of JIDs to include in the BCC list or <tt>null</tt> if no BCC list
     *        exists.
     * @param replyTo address to which all replies are requested to be sent or <tt>null</tt>
     *        indicating that they can reply to any address.
     * @param replyRoom JID of a MUC room to which responses should be sent or <tt>null</tt>
     *        indicating that they can reply to any address.
     * @param noReply true means that receivers should not reply to the message.
     * @throws XMPPErrorException if server does not support JEP-33: Extended Stanza Addressing and
     *         some JEP-33 specific features were requested.
     * @throws NoResponseException if there was no response from the server.
     * @throws FeatureNotSupportedException if special XEP-33 features where requested, but the
     *         server does not support them.
     * @throws NotConnectedException 
     */
    public static void send(XMPPConnection connection, Packet packet, List<String> to, List<String> cc, List<String> bcc,
            String replyTo, String replyRoom, boolean noReply) throws NoResponseException, XMPPErrorException, FeatureNotSupportedException, NotConnectedException {
        String serviceAddress = getMultipleRecipienServiceAddress(connection);
        if (serviceAddress != null) {
            // Send packet to target users using multiple recipient service provided by the server
            sendThroughService(connection, packet, to, cc, bcc, replyTo, replyRoom, noReply,
                    serviceAddress);
        }
        else {
            // Server does not support JEP-33 so try to send the packet to each recipient
            if (noReply || (replyTo != null && replyTo.trim().length() > 0) ||
                    (replyRoom != null && replyRoom.trim().length() > 0)) {
                // Some specified JEP-33 features were requested so throw an exception alerting
                // the user that this features are not available
                throw new FeatureNotSupportedException("Extended Stanza Addressing");
            }
            // Send the packet to each individual recipient
            sendToIndividualRecipients(connection, packet, to, cc, bcc);
        }
    }

    /**
     * Sends a reply to a previously received packet that was sent to multiple recipients. Before
     * attempting to send the reply message some checkings are performed. If any of those checkings
     * fail then an XMPPException is going to be thrown with the specific error detail.
     *
     * @param connection the connection to use to send the reply.
     * @param original   the previously received packet that was sent to multiple recipients.
     * @param reply      the new message to send as a reply.
     * @throws SmackException 
     * @throws XMPPErrorException 
     */
    public static void reply(XMPPConnection connection, Message original, Message reply) throws SmackException, XMPPErrorException
         {
        MultipleRecipientInfo info = getMultipleRecipientInfo(original);
        if (info == null) {
            throw new SmackException("Original message does not contain multiple recipient info");
        }
        if (info.shouldNotReply()) {
            throw new SmackException("Original message should not be replied");
        }
        if (info.getReplyRoom() != null) {
            throw new SmackException("Reply should be sent through a room");
        }
        // Any <thread/> element from the initial message MUST be copied into the reply.
        if (original.getThread() != null) {
            reply.setThread(original.getThread());
        }
        MultipleAddresses.Address replyAddress = info.getReplyAddress();
        if (replyAddress != null && replyAddress.getJid() != null) {
            // Send reply to the reply_to address
            reply.setTo(replyAddress.getJid());
            connection.sendPacket(reply);
        }
        else {
            // Send reply to multiple recipients
            List<String> to = new ArrayList<String>();
            List<String> cc = new ArrayList<String>();
            for (Iterator<MultipleAddresses.Address> it = info.getTOAddresses().iterator(); it.hasNext();) {
                String jid = it.next().getJid();
                to.add(jid);
            }
            for (Iterator<MultipleAddresses.Address> it = info.getCCAddresses().iterator(); it.hasNext();) {
                String jid = it.next().getJid();
                cc.add(jid);
            }
            // Add original sender as a 'to' address (if not already present)
            if (!to.contains(original.getFrom()) && !cc.contains(original.getFrom())) {
                to.add(original.getFrom());
            }
            // Remove the sender from the TO/CC list (try with bare JID too)
            String from = connection.getUser();
            if (!to.remove(from) && !cc.remove(from)) {
                String bareJID = XmppStringUtils.parseBareAddress(from);
                to.remove(bareJID);
                cc.remove(bareJID);
            }

            String serviceAddress = getMultipleRecipienServiceAddress(connection);
            if (serviceAddress != null) {
                // Send packet to target users using multiple recipient service provided by the server
                sendThroughService(connection, reply, to, cc, null, null, null, false,
                        serviceAddress);
            }
            else {
                // Server does not support JEP-33 so try to send the packet to each recipient
                sendToIndividualRecipients(connection, reply, to, cc, null);
            }
        }
    }

    /**
     * Returns the {@link MultipleRecipientInfo} contained in the specified packet or
     * <tt>null</tt> if none was found. Only packets sent to multiple recipients will
     * contain such information.
     *
     * @param packet the packet to check.
     * @return the MultipleRecipientInfo contained in the specified packet or <tt>null</tt>
     *         if none was found.
     */
    public static MultipleRecipientInfo getMultipleRecipientInfo(Packet packet) {
        MultipleAddresses extension = (MultipleAddresses) packet
                .getExtension(MultipleAddresses.ELEMENT, MultipleAddresses.NAMESPACE);
        return extension == null ? null : new MultipleRecipientInfo(extension);
    }

    private static void sendToIndividualRecipients(XMPPConnection connection, Packet packet,
            List<String> to, List<String> cc, List<String> bcc) throws NotConnectedException {
        if (to != null) {
            for (Iterator<String> it = to.iterator(); it.hasNext();) {
                String jid = it.next();
                packet.setTo(jid);
                connection.sendPacket(new PacketCopy(packet.toXML()));
            }
        }
        if (cc != null) {
            for (Iterator<String> it = cc.iterator(); it.hasNext();) {
                String jid = it.next();
                packet.setTo(jid);
                connection.sendPacket(new PacketCopy(packet.toXML()));
            }
        }
        if (bcc != null) {
            for (Iterator<String> it = bcc.iterator(); it.hasNext();) {
                String jid = it.next();
                packet.setTo(jid);
                connection.sendPacket(new PacketCopy(packet.toXML()));
            }
        }
    }

    private static void sendThroughService(XMPPConnection connection, Packet packet, List<String> to,
            List<String> cc, List<String> bcc, String replyTo, String replyRoom, boolean noReply,
            String serviceAddress) throws NotConnectedException {
        // Create multiple recipient extension
        MultipleAddresses multipleAddresses = new MultipleAddresses();
        if (to != null) {
            for (Iterator<String> it = to.iterator(); it.hasNext();) {
                String jid = it.next();
                multipleAddresses.addAddress(MultipleAddresses.TO, jid, null, null, false, null);
            }
        }
        if (cc != null) {
            for (Iterator<String> it = cc.iterator(); it.hasNext();) {
                String jid = it.next();
                multipleAddresses.addAddress(MultipleAddresses.CC, jid, null, null, false, null);
            }
        }
        if (bcc != null) {
            for (Iterator<String> it = bcc.iterator(); it.hasNext();) {
                String jid = it.next();
                multipleAddresses.addAddress(MultipleAddresses.BCC, jid, null, null, false, null);
            }
        }
        if (noReply) {
            multipleAddresses.setNoReply();
        }
        else {
            if (replyTo != null && replyTo.trim().length() > 0) {
                multipleAddresses
                        .addAddress(MultipleAddresses.REPLY_TO, replyTo, null, null, false, null);
            }
            if (replyRoom != null && replyRoom.trim().length() > 0) {
                multipleAddresses.addAddress(MultipleAddresses.REPLY_ROOM, replyRoom, null, null,
                        false, null);
            }
        }
        // Set the multiple recipient service address as the target address
        packet.setTo(serviceAddress);
        // Add extension to packet
        packet.addExtension(multipleAddresses);
        // Send the packet
        connection.sendPacket(packet);
    }

    /**
     * Returns the address of the multiple recipients service. To obtain such address service
     * discovery is going to be used on the connected server and if none was found then another
     * attempt will be tried on the server items. The discovered information is going to be
     * cached for 24 hours.
     *
     * @param connection the connection to use for disco. The connected server is going to be
     *                   queried.
     * @return the address of the multiple recipients service or <tt>null</tt> if none was found.
     * @throws NoResponseException if there was no response from the server.
     * @throws XMPPErrorException 
     * @throws NotConnectedException 
     */
    private static String getMultipleRecipienServiceAddress(XMPPConnection connection) throws NoResponseException, XMPPErrorException, NotConnectedException {
        String serviceName = connection.getServiceName();
        String serviceAddress = (String) services.get(serviceName);
        if (serviceAddress == null) {
            ServiceDiscoveryManager sdm = ServiceDiscoveryManager.getInstanceFor(connection);
            // Send the disco packet to the server itself
            DiscoverInfo info = sdm.discoverInfo(serviceName);
            // Check if the server supports XEP-33
            if (info.containsFeature(MultipleAddresses.NAMESPACE)) {
                serviceAddress = serviceName;
            }
            else {
                // Get the disco items and send the disco packet to each server item
                DiscoverItems items = sdm.discoverItems(serviceName);
                for (DiscoverItems.Item item : items.getItems()) {
                    try {
                        info = sdm.discoverInfo(item.getEntityID(), item.getNode());
                    }
                    catch (XMPPErrorException|NoResponseException e) {
                        // Don't throw this exceptions if one of the server's items fail
                        LOGGER.log(Level.WARNING,
                                        "Exception while discovering info of " + item.getEntityID()
                                                        + " node: " + item.getNode(), e);
                        continue;
                    }
                    if (info.containsFeature(MultipleAddresses.NAMESPACE)) {
                        serviceAddress = serviceName;
                        break;
                    }
                }
            }
            // Use the empty string to indicate that no service is known for this connection
            serviceAddress = serviceAddress == null ? "" : serviceAddress;
            // Cache the discovered information
            services.put(serviceName, serviceAddress);
        }

        return "".equals(serviceAddress) ? null : serviceAddress;
    }

    /**
     * Packet that holds the XML stanza to send. This class is useful when the same packet
     * is needed to be sent to different recipients. Since using the same packet is not possible
     * (i.e. cannot change the TO address of a queues packet to be sent) then this class was
     * created to keep the XML stanza to send.
     */
    private static class PacketCopy extends Packet {

        private CharSequence text;

        /**
         * Create a copy of a packet with the text to send. The passed text must be a valid text to
         * send to the server, no validation will be done on the passed text.
         *
         * @param text the whole text of the packet to send
         */
        public PacketCopy(CharSequence text) {
            this.text = text;
        }

        @Override
        public CharSequence toXML() {
            return text;
        }

    }

}
