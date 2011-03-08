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
 * Counter that tracks values over time and calculates their rolling average
 * @author Chris Winberry | chris@winberry.net
 */
public class AverageCounter
{

    private String _label; // description of what this counter tracks
    private Integer[] _samples; // array of currently recorded samples (used as ring buffer)
    private Integer _sampleSize; // maximum number of samples to track
    private Integer _samplePtrStart; // points to the oldest recorded sample in _samples
    private Integer _samplePtrEnd; // points to the newest recorded sample in _samples
    private Long _sampleTotal; // sum of all currently recorded samples

    /**
     * @param sampleSize number of samples to track for calculating an average
     * @param label description of what this counter is tracking
     */
    public AverageCounter(Integer sampleSize, String label)
    {
        if (sampleSize < 1)
        {
            throw new InvalidParameterException("sampleSize must be > 0");
        }
        _label = label;
        _sampleSize = sampleSize;
        _samples = new Integer[_sampleSize];
        _samples[0] = 0;
        _samplePtrStart = 0;
        _samplePtrEnd = 0;
        _sampleTotal = (long)0;
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
     * Gets the current calculated average
     * @return Average of the current set of sample values
     */
    public Float getCounter()
    {
        synchronized (this)
        {
            // Average is the current total divided by the current number of recorded samples
            return _sampleTotal / (float)(samplesFull()?_sampleSize:(_samplePtrEnd - _samplePtrStart + 1));
        }
    }

    /**
     * Records a new value to include in the average
     * @param value the new value to record
     */
    public void record(Integer value)
    {
        synchronized (this)
        {
            if (samplesFull())
            { // Take the oldest value and subtract it from the running total
                _sampleTotal -= _samples[_samplePtrStart];
                // Shuffle the pointers to the right one 
                _samplePtrStart = (_samplePtrStart < (_sampleSize - 1))?(_samplePtrStart + 1):0;
            }
            // Add the new value to the running total
            _sampleTotal += value;
            // Record the new sample value
            _samplePtrEnd = (_samplePtrEnd < (_sampleSize - 1))?(_samplePtrEnd + 1):0;
            _samples[_samplePtrEnd] = value;
        }
    }

}
