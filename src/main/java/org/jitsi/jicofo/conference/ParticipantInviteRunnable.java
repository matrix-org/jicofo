/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo.conference;

import org.checkerframework.checker.nullness.qual.*;
import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.codec.*;
import org.jitsi.jicofo.conference.colibri.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jingle.JingleUtils;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

/**
 * An {@link Runnable} which invites a participant to a conference.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class ParticipantInviteRunnable implements Runnable, Cancelable
{
    /**
     * The constant value used as owner attribute value of
     * {@link SSRCInfoPacketExtension} for the SSRC which belongs to the JVB.
     */
    public static final Jid SSRC_OWNER_JVB;

    static
    {
        try
        {
            SSRC_OWNER_JVB = JidCreate.from("jvb");
        }
        catch (XmppStringprepException e)
        {
            // cannot happen
            throw new RuntimeException(e);
        }
    }

    private final Logger logger;

    /**
     * The {@link JitsiMeetConferenceImpl} into which a participant will be
     * invited.
     */
    private final JitsiMeetConferenceImpl meetConference;

    @NonNull private final ColibriRequestCallback colibriRequestCallback;
    @NonNull private final ColibriSessionManager colibriSessionManager;

    /**
     * A flag which indicates whether channel allocation is canceled. Raising
     * this makes the allocation thread discontinue the allocation process and
     * return.
     */
    private volatile boolean canceled = false;

    /**
     * Whether to include a "start audio muted" extension when sending session-initiate.
     */
    private final boolean startAudioMuted;

    /**
     * Whether to include a "start video muted" extension when sending session-initiate.
     */
    private final boolean startVideoMuted;

    /**
     * Indicates whether or not this task will be doing a "re-invite". It
     * means that we're going to replace a previous conference which has failed.
     * Channels are allocated on new JVB and peer is re-invited with
     * 'transport-replace' Jingle action as opposed to 'session-initiate' in
     * regular invite.
     */
    private final boolean reInvite;

    /**
     * Override super's AbstractParticipant
     */
    @NonNull private final Participant participant;

    /**
     * {@inheritDoc}
     */
    public ParticipantInviteRunnable(
            JitsiMeetConferenceImpl meetConference,
            @NonNull ColibriRequestCallback colibriRequestCallback,
            @NonNull ColibriSessionManager colibriSessionManager,
            @NonNull Participant participant,
            boolean startAudioMuted,
            boolean startVideoMuted,
            boolean reInvite,
            Logger parentLogger)
    {
        this.meetConference = meetConference;
        this.colibriRequestCallback = colibriRequestCallback;
        this.colibriSessionManager = colibriSessionManager;
        this.startAudioMuted = startAudioMuted;
        this.startVideoMuted = startVideoMuted;
        this.reInvite = reInvite;
        this.participant = participant;
        logger = parentLogger.createChildLogger(getClass().getName());
        logger.addContext("participant", participant.getChatMember().getName());
    }

    /**
     * Entry point for the {@link ParticipantInviteRunnable} task.
     */
    @Override
    public void run()
    {
        try
        {
            doRun();
        }
        catch (Throwable e)
        {
            logger.error("Channel allocator failed: ", e);
            cancel();
        }
        finally
        {
            if (canceled)
            {
                colibriSessionManager.removeParticipant(participant);
            }

            participant.inviteRunnableCompleted(this);
        }
    }

    private void doRun()
    {
        Offer offer;

        try
        {
            offer = createOffer();
        }
        catch (UnsupportedFeatureConfigurationException e)
        {
            logger.error("Error creating offer", e);
            return;
        }
        if (canceled)
        {
            return;
        }

        ColibriAllocation colibriAllocation;
        try
        {
            colibriAllocation = colibriSessionManager.allocate(participant, offer.getContents(), reInvite);
        }
        catch (BridgeSelectionFailedException e)
        {
            // Can not find a bridge to use.
            logger.error("Can not invite participant, no bridge available: " + participant.getChatMember().getName());

            ChatRoom chatRoom = meetConference.getChatRoom();
            if (chatRoom != null
                    && !chatRoom.containsPresenceExtension(
                    BridgeNotAvailablePacketExt.ELEMENT,
                    BridgeNotAvailablePacketExt.NAMESPACE))
            {
                chatRoom.setPresenceExtension(new BridgeNotAvailablePacketExt(), false);
            }
            return;
        }
        catch (ColibriConferenceDisposedException e)
        {
            logger.error("Canceling due to ", e);
            cancel();
            return;
        }
        catch (ColibriConferenceExpiredException e)
        {
            logger.error("Canceling due to", e);
            cancel();
            if (e.getRestartConference())
            {
                colibriRequestCallback.requestFailed(e.getBridge());
            }
            return;
        }
        catch (BadColibriRequestException e)
        {
            logger.error("Canceling due to", e);
            cancel();
            return;
        }
        catch (BridgeFailedException e)
        {
            logger.error("Canceling due to", e);
            cancel();
            if (e.getRestartConference())
            {
                colibriRequestCallback.requestFailed(e.getBridge());
            }
            return;
        }
        catch (ColibriTimeoutException e)
        {
            logger.error("Canceling due to", e);
            cancel();
            colibriRequestCallback.requestFailed(e.getBridge());
            return;
        }
        catch (ColibriAllocationFailedException e)
        {
            logger.error("Canceling due to unexpected exception", e);
            cancel();
            return;
        }


        if (canceled)
        {
            return;
        }

        offer = updateOffer(offer, colibriAllocation);
        if (canceled)
        {
            return;
        }

        try
        {
            invite(offer, colibriAllocation);
        }
        catch (SmackException.NotConnectedException e)
        {
            logger.error("Failed to invite participant: ", e);
        }
    }

    /**
     * Raises the {@code canceled} flag, which causes the thread to not continue
     * with the allocation process.
     */
    @Override
    public void cancel()
    {
        canceled = true;
    }

    @Override
    public String toString()
    {
        return String.format(
                "%s[%s]@%d",
                this.getClass().getSimpleName(),
                participant,
                hashCode());
    }

    /**
     * {@inheritDoc}
     */
    private Offer createOffer()
        throws UnsupportedFeatureConfigurationException
    {
        // Feature discovery
        List<String> features = meetConference.getClientXmppProvider().discoverFeatures(participant.getMucJid());
        participant.setSupportedFeatures(features);

        JitsiMeetConfig config = meetConference.getConfig();

        OfferOptions offerOptions = new OfferOptions();
        OfferOptionsKt.applyConstraints(offerOptions, config);
        OfferOptionsKt.applyConstraints(offerOptions, participant);
        // Enable REMB only when TCC is not enabled.
        if (!offerOptions.getTcc() && participant.hasRembSupport())
        {
            offerOptions.setRemb(true);
        }

        return new Offer(new ConferenceSourceMap(), JingleOfferFactory.INSTANCE.createOffer(offerOptions));
    }

    /**
     * {@inheritDoc}
     */
    private void invite(Offer offer, ColibriAllocation colibriAllocation)
        throws SmackException.NotConnectedException
    {
        /*
           This check makes sure that when we're trying to invite
           new participant:
           - the conference has not been disposed in the meantime
           - he's still in the room
           - we have managed to send Jingle session-initiate
           We usually expire channels when participant leaves the MUC, but we
           may not have channel information set, so we have to expire it
           here.
        */
        boolean expireChannels = false;
        Jid address = participant.getMucJid();

        ChatRoom chatRoom = meetConference.getChatRoom();
        if (chatRoom == null)
        {
            // Conference disposed
            logger.info("Expiring " + address + " channels - conference disposed");

            expireChannels = true;
        }
        else if (meetConference.findMember(address) == null)
        {
            // Participant has left the room
            logger.info("Expiring " + address + " channels - participant has left");

            expireChannels = true;
        }
        else if (!canceled)
        {
            if (!doInviteOrReinvite(address, offer, colibriAllocation))
            {
                expireChannels = true;
            }
        }

        if (expireChannels || canceled)
        {
            // Whether another thread intentionally canceled us, or there was
            // a failure to invite the participant on the jingle level, we will
            // not trigger a retry here.
            meetConference.onInviteFailed(this);
        }
        else if (reInvite)
        {
            colibriSessionManager.updateParticipant(participant, null, null, null);
        }

        // TODO: include force-mute in the initial allocation, instead of sending 2 additional colibri messages.
        if (chatRoom != null && !participant.hasModeratorRights())
        {
            // if participant is not muted, but needs to be
            if (chatRoom.isAvModerationEnabled(MediaType.AUDIO))
            {
                meetConference.muteParticipant(participant, MediaType.AUDIO);
            }

            if (chatRoom.isAvModerationEnabled(MediaType.VIDEO))
            {
                meetConference.muteParticipant(participant, MediaType.VIDEO);
            }
        }
    }

    /**
     * Invites or re-invites (based on the value of {@link #reInvite}) the
     * {@code participant} to the jingle session.
     * Creates and sends the appropriate Jingle IQ ({@code session-initiate} for
     * and invite or {@code transport-replace} for a re-invite) and sends it to
     * the {@code participant}. Blocks until a response is received or a timeout
     * occurs.
     *
     * @param address the destination JID.
     * @param offer The description of the offer to send (sources and a list of {@link ContentPacketExtension}s).
     * @return {@code false} on failure.
     * @throws SmackException.NotConnectedException if we are unable to send a packet because the XMPP connection is not
     * connected.
     */
    private boolean doInviteOrReinvite(Jid address, Offer offer, ColibriAllocation colibriAllocation)
        throws SmackException.NotConnectedException
    {
        OperationSetJingle jingle = meetConference.getJingle();
        JingleSession jingleSession = participant.getJingleSession();
        boolean initiateSession = !reInvite || jingleSession == null;
        boolean ack;
        List<ExtensionElement> additionalExtensions = new ArrayList<>();

        if (startAudioMuted || startVideoMuted)
        {
            StartMutedPacketExtension startMutedExt = new StartMutedPacketExtension();
            startMutedExt.setAudioMute(startAudioMuted);
            startMutedExt.setVideoMute(startVideoMuted);
            additionalExtensions.add(startMutedExt);
        }

        // Include info about the BridgeSession which provides the transport
        additionalExtensions.add(new BridgeSessionPacketExtension(
                colibriAllocation.getBridgeSessionId(),
                colibriAllocation.getRegion()));

        if (initiateSession)
        {
            logger.info("Sending session-initiate to: " + address);
            ack = jingle.initiateSession(
                    address,
                    offer.getContents(),
                    additionalExtensions,
                    meetConference,
                    offer.getSources(),
                    ConferenceConfig.config.getUseJsonEncodedSources() && participant.supportsJsonEncodedSources());
        }
        else
        {
            logger.info("Sending transport-replace to: " + address);
            // will throw OperationFailedExc if XMPP connection is broken
            ack = jingle.replaceTransport(
                    jingleSession,
                    offer.getContents(),
                    additionalExtensions,
                    offer.getSources(),
                    ConferenceConfig.config.getUseJsonEncodedSources() && participant.supportsJsonEncodedSources());
        }

        if (!ack)
        {
            // Failed to invite
            logger.info(
                "Expiring " + address + " channels - no RESULT for "
                    + (initiateSession ? "session-initiate"
                    : "transport-replace"));
            return false;
        }

        return true;
    }

    private @NonNull Offer updateOffer(Offer offer, ColibriAllocation colibriAllocation)
    {
        // Take all sources from participants in the conference.
        ConferenceSourceMap conferenceSources = meetConference.getSources()
                .copy()
                .strip(ConferenceConfig.config.stripSimulcast(), true)
                .stripByMediaType(participant.getSupportedMediaTypes());
        // Remove the participant's own sources (if they're present)
        conferenceSources.remove(participant.getMucJid());
        // Add sources advertised by the bridge.
        conferenceSources.add(colibriAllocation.getSources());

        for (ContentPacketExtension cpe : offer.getContents())
        {
            try
            {
                // Remove empty transport PE
                IceUdpTransportPacketExtension empty = cpe.getFirstChildOfType(IceUdpTransportPacketExtension.class);
                cpe.getChildExtensions().remove(empty);

                IceUdpTransportPacketExtension copy =
                        IceUdpTransportPacketExtension.cloneTransportAndCandidates(
                                colibriAllocation.getTransport(),
                                true);

                if ("data".equalsIgnoreCase(cpe.getName()))
                {
                    // FIXME: hardcoded
                    SctpMapExtension sctpMap = new SctpMapExtension();
                    sctpMap.setPort(5000);
                    sctpMap.setProtocol(SctpMapExtension.Protocol.WEBRTC_CHANNEL);
                    sctpMap.setStreams(1024);

                    copy.addChildExtension(sctpMap);
                }

                cpe.addChildExtension(copy);
            }
            catch (Exception e)
            {
                logger.error(e, e);
            }

            RtpDescriptionPacketExtension rtpDescPe = JingleUtils.getRtpDescription(cpe);
            if (rtpDescPe != null)
            {
                // rtcp-mux is always used
                rtpDescPe.addChildExtension(new JingleRtcpmuxPacketExtension());
            }
        }


        return new Offer(conferenceSources, offer.getContents());
    }

    /**
     * @return the {@link Participant} associated with this
     * {@link ParticipantInviteRunnable}.
     */
    public @NotNull Participant getParticipant()
    {
        return participant;
    }
}
