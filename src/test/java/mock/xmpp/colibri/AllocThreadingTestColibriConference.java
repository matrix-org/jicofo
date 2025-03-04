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
package mock.xmpp.colibri;

import edu.umd.cs.findbugs.annotations.*;
import org.jitsi.jicofo.conference.colibri.v1.*;
import org.jitsi.xmpp.extensions.colibri.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;

import java.util.concurrent.*;

/**
 * Extended version of <tt>ColibriConferenceImpl</tt> that allows to block
 * threads at certain points of channel allocation process.
 *
 * @author Pawel Domas
 */
public class AllocThreadingTestColibriConference
    extends ColibriConferenceImpl
{

    /**
     * Stores endpoint name of conference creator.
     */
    private String confCreator;

    /**
     * Blocking queue used to put and acquire conference creator endpoint.
     */
    private final BlockingQueue<String> confCreatorQueue = new ArrayBlockingQueue<>(1);

    /**
     * Indicates whether creator thread should be suspended before it sends it's
     * request. Call {@link #resumeConferenceCreate()} to resume it's job.
     */
    private boolean blockConferenceCreation;

    /**
     * Lock used to block conference creator's thread before it sends "create"
     * request.
     */
    private final Object createConferenceSync = new Object();

    /**
     * The queue used to put and acquire endpoint names arriving on
     * {@code ColibriConferenceImpl.ConferenceCreationSemaphore}.
     * Used to verify if all running threads have reached the semaphore.
     */
    private final BlockingQueue<String> createConfSemaphoreQueue = new LinkedBlockingQueue<>();

    /**
     * Blocking queue used to put and acquire endpoints that have sent it's
     * request packets.
     */
    private final BlockingQueue<String> requestsSentQueue = new LinkedBlockingQueue<>();

    /**
     * Indicates if threads should be blocked before response is received.
     */
    private boolean blockResponseReceive;

    /**
     * Lock used to stop threads before they get their response packet.
     */
    private final Object blockResponseReceiveLock = new Object();

    /**
     * Blocking queue used to put and acquire endpoints that have received their
     * response packets. Used to verify if all threads have received their
     * response packets.
     */
    private final BlockingQueue<String> responseReceivedQueue = new LinkedBlockingQueue<>();

    /**
     * Creates new instance of <tt>ColibriConferenceImpl</tt>.
     *
     * @param connection XMPP connection object that wil be used by new
     *                   instance.
     */
    public AllocThreadingTestColibriConference(AbstractXMPPConnection connection)
    {
        super(connection);
    }

    /**
     * Sets whether or not conference creator thread should be blocked before
     * before it manages to sent it's "create" request.
     *
     * @param block <tt>true</tt> to block creator thread or <tt>false</tt> to
     *              leave it alone.
     */
    public void blockConferenceCreator(boolean block)
    {
        blockConferenceCreation = block;
    }

    /**
     * Releases conference create thread if it was blocked.
     */
    public void resumeConferenceCreate()
    {
        synchronized (createConferenceSync)
        {
            blockConferenceCreation = false;

            createConferenceSync.notifyAll();
        }
    }

    /**
     * Tries to obtain the name of conference creator endpoint.
     *
     * @return the name of conference creator endpoint or <tt>null</tt> if we
     * have failed to obtain within 5 seconds timeout.
     *
     * @throws InterruptedException if thread has been interrupted while waiting
     */
    public String obtainConferenceCreator()
        throws InterruptedException
    {
        return confCreatorQueue.poll(5, TimeUnit.SECONDS);
    }

    @Override
    protected boolean acquireCreateConferenceSemaphore(String endpointId)
        throws ColibriException
    {
        createConfSemaphoreQueue.add(endpointId);

        boolean isCreator =  super.acquireCreateConferenceSemaphore(endpointId);

        if (isCreator)
        {
            confCreator = endpointId;
            confCreatorQueue.add(endpointId);
        }

        return isCreator;
    }

    public int allocRequestsSentCount()
    {
        return requestsSentQueue.size();
    }

    public void blockResponseReceive(boolean blockResponseReceive)
    {
        this.blockResponseReceive = blockResponseReceive;
    }

    public String nextRequestSent(long timeoutSeconds)
        throws InterruptedException
    {
        return requestsSentQueue.poll(timeoutSeconds, TimeUnit.SECONDS);
    }

    public String nextResponseReceived(long timeoutSeconds)
        throws InterruptedException
    {
        return responseReceivedQueue.poll(timeoutSeconds, TimeUnit.SECONDS);
    }

    @SuppressFBWarnings("WA_NOT_IN_LOOP")
    @Override
    protected Stanza sendAllocRequest(String endpointId,
                                      ColibriConferenceIQ request)
        throws ColibriException
    {
        boolean isCreator = confCreator.equals(endpointId);
        synchronized (createConferenceSync)
        {
            if (isCreator && blockConferenceCreation)
            {
                try
                {
                    createConferenceSync.wait();
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }
        }

        requestsSentQueue.add(endpointId);

        Stanza response = super.sendAllocRequest(endpointId, request);

        synchronized (blockResponseReceiveLock)
        {
            if (blockResponseReceive && !isCreator)
            {
                try
                {
                    blockResponseReceiveLock.wait();
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }
        }

        responseReceivedQueue.add(endpointId);

        return response;
    }

    public void resumeResponses()
    {
        synchronized (blockResponseReceiveLock)
        {
            blockResponseReceive = false;

            blockResponseReceiveLock.notifyAll();
        }
    }
}
