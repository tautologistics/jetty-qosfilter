/*
 * ========================================================================
 * Copyright (c) 2011 Barnes & Noble.com llc
 * ------------------------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *  You may elect to redistribute this code under either of these licenses.
 * ========================================================================
 */

package com.bn.services.filters;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;

import com.bn.services.utils.AverageCounter;
import com.bn.services.utils.RateCounter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QoS Servlet Filter based on request priority levels
 * @author Chris Winberry | chris@winberry.net
 */
public class QoSFilter implements Filter
{

    // Defines
    private final static Integer MAX_PRIORITY_LEVEL = 1; // The highest priority level for a request
    private final static Integer MAGIC_PRIORITY_LEVEL = 0; // Special priority level that bypasses the queue and pool limits
    
    // Config keys
    private final static String INITKEY_MIN_PRIORITY_LEVEL = "minpriority";
    private final static String INITKEY_MAX_REQUESTS = "maxreq";
    private final static String INITKEY_MAX_QUEUE_ITEMS = "maxqueue";
    private final static String INITKEY_LOCK_TIMEOUT = "locktimeout";
    private final static String INITKEY_REQUEST_TIMEOUT = "requesttimeout";
    private final static String INITKEY_REQUEST_PRIORITY_TIMEOUT = "prioritytimeout";

    // Defaults
    private final static Integer DEFAULT_MIN_PRIORITY_LEVEL = 5; // Minimum priority level allowed on a request
    private final static Integer DEFAULT_MAX_REQUESTS = 1; // Maximum number of concurrent requests going through filter
    private final static Integer DEFAULT_MAX_QUEUE_ITEMS = 100; // Maximum number items allowed in the queue
    private final static Integer DEFAULT_LOCK_TIMEOUT = 50; // Number of MS for a request to wait for a permit from the semaphore
    private final static Integer DEFAULT_REQUEST_TIMEOUT = 2000; // Number of MS before a pending request is killed off
    private final static Integer DEFAULT_REQUEST_PRIORITY_TIMEOUT = 500; // Number of MS for a request to wait at a given priority level

    // Configuration
    private Integer _MIN_PRIORITY_LEVEL; // Minimum priority level allowed on a request
    private Integer _MAX_REQUESTS; // Maximum number of concurrent requests going through filter
    private Integer _MAX_QUEUE_ITEMS; // Maximum number items allowed in the queue
    private Integer _LOCK_TIMEOUT; // Number of MS for a request to wait for a permit from the semaphore
    private Integer _REQUEST_TIMEOUT; // Number of MS before a pending request is killed off
    private Integer _REQUEST_PRIORITY_TIMEOUT; // Number of MS for a request to wait at a given priority level

    // Request attribute keys
    private final String _attrKeyRequestStartTime = "QoSFilter@start_time@" + this.hashCode(); // Time at which request originally came in
    private final String _attrKeyRequestServiceTime = "QoSFilter@service_time@" + this.hashCode(); // Time at which request started being serviced
    private final String _attrKeyOriginalPriority = "QoSFilter@original_priority@" + this.hashCode(); // Current request priority level
    private final String _attrKeyCurrentPriority = "QoSFilter@current_priority@" + this.hashCode(); // Original request priority level
    private final String _attrKeyExpired = "QoSFilter@expired@" + this.hashCode(); // Flag indicating whether the request timed out

    // Misc
    private Semaphore _requestSlots; // Tracks number of currently handled requests
    private ContinuationListener _continuationListener; // Handles timeout/completion events of a continuation
    private LinkedBlockingQueue<Continuation>[] _requestQueue; // Array of queues (one per priority level) that hold waiting requests
    private Integer _queueSize; // Tracks the count items in all the queues. Used to determine if an attempt bypass the queue should be made
    private RateCounter _requestRateCounter; // Tracks requests/sec
    private AverageCounter _reponseTimeCounter; // Tracks average response time
    private static final Logger LOGGER = LoggerFactory.getLogger(QoSFilter.class); // slf4j logger

    /**
     * Default constructor.
     */
    public QoSFilter()
    {
    }

    /**
     * @see Filter#init(FilterConfig)
     */
    @SuppressWarnings("unchecked")
    public void init(FilterConfig filterConfig) throws ServletException
    {
        String tmpConfigValue; // Holds the raw init-param values from the config

        // Override the defaults with anything defined in the filter parameters
        tmpConfigValue = filterConfig.getInitParameter(INITKEY_MIN_PRIORITY_LEVEL);
        _MIN_PRIORITY_LEVEL = (tmpConfigValue != null)?Integer.parseInt(tmpConfigValue):DEFAULT_MIN_PRIORITY_LEVEL;

        tmpConfigValue = filterConfig.getInitParameter(INITKEY_MAX_REQUESTS);
        _MAX_REQUESTS = (tmpConfigValue != null)?Integer.parseInt(tmpConfigValue):DEFAULT_MAX_REQUESTS;

        tmpConfigValue = filterConfig.getInitParameter(INITKEY_MAX_QUEUE_ITEMS);
        _MAX_QUEUE_ITEMS = (tmpConfigValue != null)?Integer.parseInt(tmpConfigValue):DEFAULT_MAX_QUEUE_ITEMS;

        tmpConfigValue = filterConfig.getInitParameter(INITKEY_LOCK_TIMEOUT);
        _LOCK_TIMEOUT = (tmpConfigValue != null)?Integer.parseInt(tmpConfigValue):DEFAULT_LOCK_TIMEOUT;

        tmpConfigValue = filterConfig.getInitParameter(INITKEY_REQUEST_TIMEOUT);
        _REQUEST_TIMEOUT = (tmpConfigValue != null)?Integer.parseInt(tmpConfigValue):DEFAULT_REQUEST_TIMEOUT;

        tmpConfigValue = filterConfig.getInitParameter(INITKEY_REQUEST_PRIORITY_TIMEOUT);
        _REQUEST_PRIORITY_TIMEOUT = (tmpConfigValue != null)?Integer.parseInt(tmpConfigValue):DEFAULT_REQUEST_PRIORITY_TIMEOUT;

        // Dump out the effective config parameters
        LOGGER.info("_MIN_PRIORITY_LEVEL: " + _MIN_PRIORITY_LEVEL);
        LOGGER.info("_MAX_REQUESTS: " + _MAX_REQUESTS);
        LOGGER.info("_MAX_QUEUE_ITEMS: " + _MAX_QUEUE_ITEMS);
        LOGGER.info("_LOCK_TIMEOUT: " + _LOCK_TIMEOUT);
        LOGGER.info("_REQUEST_TIMEOUT: " + _REQUEST_TIMEOUT);
        LOGGER.info("_REQUEST_PRIORITY_TIMEOUT: " + _REQUEST_PRIORITY_TIMEOUT);

        _queueSize = 0;

        _requestSlots = new Semaphore(_MAX_REQUESTS,true);

        // Populate array with queues
        _requestQueue = new LinkedBlockingQueue[_MIN_PRIORITY_LEVEL];
        for (Integer i = 0; i <= (_MIN_PRIORITY_LEVEL - MAX_PRIORITY_LEVEL); i++)
        {
            _requestQueue[i] = new LinkedBlockingQueue<Continuation>();
        }

        final QoSFilter filter = this;
        // Handles events fired by each request that comes through
        _continuationListener = new ContinuationListener()
        {

            @Override
            public void onComplete(Continuation continuation)
            {
                // Record the wait and service time
                Long now = System.currentTimeMillis();
                Long startTime = filter.getRequestStartTime(continuation);
                Long serviceTime = filter.getRequestServiceTime(continuation);

                // Check if the request is expired (waited longer than _REQUEST_TIMEOUT for a slot)
                if (!filter.getRequestExpired(continuation))
                {
                    _requestRateCounter.record();
                    _reponseTimeCounter.record((int)(now - serviceTime));
                    LOGGER.info("REQUEST TIME" + " wait:" + (serviceTime - startTime) + " response:" + (now - serviceTime) + " expired:no" + " requestRate:"
                            + _requestRateCounter.getCounter() + "r/s" + " avgResponse:" + _reponseTimeCounter.getCounter() + "ms");
                    // It was not expired so it occupied a request slot
                    filter.releaseRequestSlot();
                    // Now that a request is completed, process the queue
                    processQueue();
                }
                else
                {
                    LOGGER.info("REQUEST TIME" + " wait:" + (now - startTime) + " response:0" + " expired:yes" + " requestRate:" + _requestRateCounter.getCounter()
                            + "r/s" + " avgResponse:" + _reponseTimeCounter.getCounter() + "ms");
                }
            }

            @Override
            public void onTimeout(Continuation continuation)
            {
                // Request did not get serviced. Remove from queue and it will get picked up again
                filter.removeFromQueue(continuation);
            }
        };

        _requestRateCounter = new RateCounter(100,"Average requests per second");
        _reponseTimeCounter = new AverageCounter(100,"Average response time");
    }

    @Override
    public void destroy()
    {
    }

    /**
     * @see Filter#doFilter(ServletRequest, ServletResponse, FilterChain)
     */
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        try
        {
            Continuation continuation = ContinuationSupport.getContinuation(request);

            // If the request is resumed and not timed out, service it
            if (continuation.isResumed() && !continuation.isExpired())
            {
                setRequestServiceTime(continuation);
                chain.doFilter(request,response);
                return;
            }

            // Is this the request's first pass through?
            if (continuation.isInitial())
            {
                Integer reqPriority = calcPriority((HttpServletRequest)request);

                // If this request has the magic priority level, process it immediately
                if (reqPriority == MAGIC_PRIORITY_LEVEL)
                {
                    setRequestServiceTime(continuation);
                    chain.doFilter(request,response);
                    return;
                }

                // Save some data about the request
                // setRequestId(continuation);
                setRequestStartTime(continuation);
                setRequestOriginalPriority(continuation,reqPriority);
                setRequestCurrentPriority(continuation,reqPriority);
                setRequestExpired(continuation,false);

                // Create a continuation and set its timeout
                continuation.addContinuationListener(_continuationListener);
                continuation.setTimeout(_REQUEST_PRIORITY_TIMEOUT);

                // If the queues are empty and a slot is free, dispatch it immediately
                if (_queueSize < 1 && acquireRequestSlot())
                {
                    setRequestServiceTime(continuation);
                    chain.doFilter(request,response);
                    return;
                }

                // Queue the request
                if (!addToQueue(continuation))
                {
                    sendExpiredErrorResponse(continuation);
                }
            }
            else
            { // Current request has timed out in the queue
                Integer reqCurrentPriority = getRequestCurrentPriority(continuation);

                /*
                 * If the request is timed out and its current request priority is already at the highest level, then the request is now expired (wait time >
                 * _REQUEST_TIMEOUT). Send an error response and flag it as expired (setRequestExpired)
                 */
                if (reqCurrentPriority <= MAX_PRIORITY_LEVEL)
                {
                    sendExpiredErrorResponse(continuation);
                    return;
                }

                // Bump the priority level and save it back the request
                reqCurrentPriority--;
                setRequestCurrentPriority(continuation,reqCurrentPriority);

                if (reqCurrentPriority > MAX_PRIORITY_LEVEL)
                {
                    // Use the standard priority level timeout
                    continuation.setTimeout(_REQUEST_PRIORITY_TIMEOUT);
                }
                else
                {
                    // Request is already at highest priority so set timeout to whatever time it has left before expiration
                    continuation.setTimeout(_REQUEST_TIMEOUT - (System.currentTimeMillis() - getRequestStartTime(continuation)));
                }

                // Requeue the request
                if (!addToQueue(continuation))
                {
                    sendExpiredErrorResponse(continuation);
                }
            }
        }
        finally
        {
            processQueue();
        }
    }

    // Processes the request queue based on available request slots
    private void processQueue()
    {
        // Process the queue as long as it has items and slots are available
        while (_queueSize > 0 && acquireRequestSlot())
        {
            Continuation continuation = getNextQueuedRequest();
            if (continuation != null && continuation.isSuspended())
            {
                // Got a request from the queue and request is suspended
                continuation.resume();
            }
            else
            {
                // No requests to process in queue, give back the slot
                releaseRequestSlot();
                return;
            }
        }
    }

    // Sends a generic "unavailable" response to the client
    private void sendExpiredErrorResponse(Continuation continuation)
    {
        setRequestExpired(continuation,true);
        try
        {
            ((HttpServletResponse)continuation.getServletResponse()).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        catch (IOException e)
        {
        }
    }

    // Attempts to get a request slot, returns true if successful
    private boolean acquireRequestSlot()
    {
        try
        {
            return _requestSlots.tryAcquire(_LOCK_TIMEOUT,TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            return false;
        }
    }

    // Releases a request slot back into the available pool
    private void releaseRequestSlot()
    {
        _requestSlots.release();
    }

    // Adds a request (actually its continuation) to the request queue
    private boolean addToQueue(Continuation continuation)
    {
        if (_queueSize >= _MAX_QUEUE_ITEMS)
        {
            return false;
        }
        synchronized (this)
        {
            _queueSize++;
        }
        // Get the request's current priority and add it to the corresponding queue
        Integer priority = getRequestCurrentPriority(continuation);
        _requestQueue[priority - MAX_PRIORITY_LEVEL].add(continuation);
        continuation.suspend();

        return true;
    }

    // Removes a specific request/continuation from the request queue 
    private void removeFromQueue(Continuation continuation)
    {
        // Get the request's current priority and remove it from the corresponding queue
        Integer priority = getRequestCurrentPriority(continuation);
        // _requestQueue[priority - _MAX_PRIORITY_LEVEL].remove(continuation);
        if (_requestQueue[priority - MAX_PRIORITY_LEVEL].remove(continuation))
        {
            synchronized (this)
            {
                _queueSize--;
            }
        }
    }

    // Pulls the highest request/continuation from the request queue, and returns null if the queue is empty
    private Continuation getNextQueuedRequest()
    {
        // Scan the queue array for a queue with items in it
        for (Integer i = 0; i < _requestQueue.length; i++)
        {
            if (_requestQueue[i].size() > 0)
            {
                Continuation continuation = _requestQueue[i].poll();
                // If we get a request off the queue, return it
                if (continuation != null)
                {
                    synchronized (this)
                    {
                        _queueSize--;
                    }
                    if (continuation.isSuspended())
                    {
                        return continuation;
                    }
                }
            }
        }
        return null;
    }

    // Sets an attribute on the request containing the current time
    private void setRequestStartTime(Continuation continuation)
    {
        continuation.setAttribute(_attrKeyRequestStartTime,System.currentTimeMillis());
    }

    // Gets the time at which the request was made; returns 0 if no time has been set
    private Long getRequestStartTime(Continuation continuation)
    {
        if (continuation.getAttribute(_attrKeyRequestStartTime) == null)
        {
            return new Long(0);
        }
        return (Long)continuation.getAttribute(_attrKeyRequestStartTime);
    }

    // Sets an attribute on the request containing the time at which the request began being serviced
    private void setRequestServiceTime(Continuation continuation)
    {
        continuation.setAttribute(_attrKeyRequestServiceTime,System.currentTimeMillis());
    }

    // Gets the time at which the request was serviced; returns 0 if no time has been set
    private Long getRequestServiceTime(Continuation continuation)
    {
        if (continuation.getAttribute(_attrKeyRequestServiceTime) == null)
        {
            return new Long(0);
        }
        return (Long)continuation.getAttribute(_attrKeyRequestServiceTime);
    }

    // Sets an attribute on the request containing the original priority level
    private void setRequestOriginalPriority(Continuation continuation, Integer priority)
    {
        continuation.setAttribute(_attrKeyOriginalPriority,priority);
    }

    // Gets the original priority level of the request; returns 0 if no priority has been set
    private Integer getRequestOriginalPriority(Continuation continuation)
    {
        if (continuation.getAttribute(_attrKeyOriginalPriority) == null)
        {
            return 0;
        }
        return (Integer)continuation.getAttribute(_attrKeyOriginalPriority);
    }

    // Sets an attribute on the request containing the current priority level
    private void setRequestCurrentPriority(Continuation continuation, Integer priority)
    {
        continuation.setAttribute(_attrKeyCurrentPriority,priority);
    }

    // Gets the current priority level of the request; returns 0 if no priority has been set
    private Integer getRequestCurrentPriority(Continuation continuation)
    {
        if (continuation.getAttribute(_attrKeyCurrentPriority) == null)
        {
            return 0;
        }
        return (Integer)continuation.getAttribute(_attrKeyCurrentPriority);
    }

    // Sets an attribute on the request representing the expiration state of the request
    private void setRequestExpired(Continuation continuation, Boolean expired)
    {
        continuation.setAttribute(_attrKeyExpired,expired);
    }

    // Gets the expiration state of the request; returns false if no value has been set
    private Boolean getRequestExpired(Continuation continuation)
    {
        if (continuation.getAttribute(_attrKeyExpired) == null)
        {
            return false;
        }
        return (Boolean)continuation.getAttribute(_attrKeyExpired);
    }

//    private String formatContinuationState(Continuation continuation)
//    {
//        return "ContState: "
//                // "**" + getRequestId(continuation) + "**"
//                + " queueSize=" + _queueSize + " slots=" + _requestSlots.availablePermits() + " isInitial=" + continuation.isInitial() + " isExpired="
//                + continuation.isExpired() + " isResumed=" + continuation.isResumed() + " isSuspended=" + continuation.isSuspended() + " priority="
//                + getRequestCurrentPriority(continuation) + " start=" + getRequestStartTime(continuation) + " now=" + System.currentTimeMillis();
//    }

    /**
     * Calculates the initial (original) priority level of incoming request
     * @param request HttpServletRequest the request to calculate a priority on
     * @return Priority between _MIN_PRIORITY_LEVEL and MAX_PRIORITY_LEVEL, inclusive
     */
    public Integer calcPriority(HttpServletRequest request)
    {
        /*
         * Currently this tries to extract a number from a querystring parameter called "priority". Eventually this will need to consider other properties of
         * the request to get a priority
         */
        try
        {
            List<NameValuePair> oldParams = URLEncodedUtils.parse((new URL(request.getRequestURL().toString() + "?" + request.getQueryString())).toURI(),null);
            Iterator<NameValuePair> paramIter = oldParams.iterator();
            while (paramIter.hasNext())
            {
                NameValuePair param = paramIter.next();
                if (param.getName().equals("priority"))
                {
                    Integer priority = Integer.parseInt(param.getValue());
                    if (priority > _MIN_PRIORITY_LEVEL || priority < MAX_PRIORITY_LEVEL)
                    {
                        return _MIN_PRIORITY_LEVEL;
                    }
                    return priority;
                }
            }
        }
        catch (MalformedURLException e)
        {
        }
        catch (URISyntaxException e)
        {
        }

        return _MIN_PRIORITY_LEVEL;
    }

}
