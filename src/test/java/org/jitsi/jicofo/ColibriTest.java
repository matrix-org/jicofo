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
package org.jitsi.jicofo;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import mock.jvb.*;
import mock.xmpp.*;
import org.jitsi.jicofo.codec.*;
import org.jitsi.jicofo.conference.colibri.v1.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.junit.jupiter.api.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

/**
 * Tests colibri tools used for channel management.
 *
 * @author Pawel Domas
 */

public class ColibriTest
{
    private final JicofoHarness harness = new JicofoHarness();

    @AfterEach
    public void tearDown()
    {
        harness.shutdown();
    }

    @Test
    public void testChannelAllocation()
        throws Exception
    {
        MockXmppConnection connection = harness.getXmppProvider().getXmppConnection();
        Jid bridgeJid = JidCreate.from("bridge.example.com");
        MockVideobridge mockBridge = new MockVideobridge(new MockXmppConnection(bridgeJid));
        mockBridge.start();

        ColibriConference colibriConf = new ColibriConferenceImpl(connection);
        colibriConf.setName(JidCreate.entityBareFrom("foo@bar.com/zzz"));
        colibriConf.setJitsiVideobridge(bridgeJid);

        OfferOptions offerOptions = new OfferOptions();
        offerOptions.setSctp(false);
        offerOptions.setRtx(false);

        List<ContentPacketExtension> contents = JingleOfferFactory.INSTANCE.createOffer(offerOptions);

        String peer1 = "endpoint1";
        String peer2 = "endpoint2";

        ColibriConferenceIQ peer1Channels = colibriConf.createColibriChannels(peer1, null, true, contents);
        ColibriConferenceIQ peer2Channels = colibriConf.createColibriChannels(peer2, null, true, contents);

        assertEquals(2, countChannels(peer1Channels),
            "Peer 1 should have 2 channels allocated");
        assertEquals(2, countChannels(peer2Channels),
            "Peer 2 should have 2 channels allocated");

        assertEquals(1, peer1Channels.getChannelBundles().size(),
            "Peer 1 should have a single bundle allocated");
        assertEquals(1, peer2Channels.getChannelBundles().size(),
            "Peer 2 should have a single bundle allocated");

        colibriConf.expireChannels(peer1Channels);
        colibriConf.expireChannels(peer2Channels);
        mockBridge.stop();
    }

    private static int countChannels(ColibriConferenceIQ conferenceIq)
    {
        int count = 0;
        for (ColibriConferenceIQ.Content content : conferenceIq.getContents())
        {
            count += content.getChannelCount();
            count += content.getSctpConnections().size();
        }
        return count;
    }
}
