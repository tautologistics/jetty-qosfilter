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

package com.bn.services.utils;

import java.security.InvalidParameterException;

/**
 * Counter that tracks events over time and calculates their frequency as events per second
 * @author Chris Winberry | chris@winberry.net
 */
public class RateCounter
{

    private String _label; // description of what this counter tracks
    private Long[] _samples; // array of currently recorded samples (used as ring buffer)
    private Integer _sampleSize; // maximum number of samples to tracks
    private Integer _samplePtrStart; // points to the oldest recorded sample in _samples
    private Integer _samplePtrEnd; // points to the newest recorded sample in _samples

    /**
     * @param sampleSize number of samples to track for calculating rate
     * @param label description of what this counter is tracking
     */
    public RateCounter(Integer sampleSize, String label)
    {
        if (sampleSize < 1)
        {
            throw new InvalidParameterException("sampleSize must be > 0");
        }
        _label = label;
        _sampleSize = sampleSize;
        _samples = new Long[_sampleSize];
        _samples[0] = System.currentTimeMillis();
        _samplePtrStart = 0;
        _samplePtrEnd = 0;
    }

    // Determines of the samples array is fully populated
    private Boolean samplesFull()
    {
        return _samplePtrEnd == (_sampleSize - 1) || _samplePtrEnd < _samplePtrStart;
    }

    /**
     * Gets the label set for the counter instance
     * @return Description of what this counter is tracking
     */
    public String getLabel()
    {
        return _label;
    }

    /**
     * Gets the current calculated rate
     * @return Rate of the current set of sampled events
     */
    public Float getCounter()
    {
        synchronized (this)
        {
            // Determine timespan from newest and oldest sampled time
            Long timeSpan = _samples[_samplePtrEnd] - _samples[_samplePtrStart];
            if (timeSpan == 0)
            {
                return new Float(0);
            }
            // Rate is timespan devided by current number of recorded samples
            return (float)(samplesFull()?_sampleSize:(_samplePtrEnd - _samplePtrStart + 1)) / timeSpan * 1000;
        }
    }

    /**
     * Records a new event for the current time
     */
    public void record()
    {
        synchronized (this)
        {
            if (samplesFull())
            { // Shuffle the pointers to the right one if _samples is full 
                _samplePtrStart = (_samplePtrStart < (_sampleSize - 1))?(_samplePtrStart + 1):0;
            }
            // Record the new sample
            _samplePtrEnd = (_samplePtrEnd < (_sampleSize - 1))?(_samplePtrEnd + 1):0;
            _samples[_samplePtrEnd] = System.currentTimeMillis();
        }
    }

}
