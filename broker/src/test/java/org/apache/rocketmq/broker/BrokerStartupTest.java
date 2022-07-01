/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.broker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

import org.junit.Assert;
import org.junit.Test;

public class BrokerStartupTest {

    private String storePathRootDir = ".";

    @Test
    public void testProperties2SystemEnv() throws NoSuchMethodException, InvocationTargetException,
            IllegalAccessException {
        Properties properties = new Properties();
        Class<BrokerStartup> clazz = BrokerStartup.class;
        Method method = clazz.getDeclaredMethod("properties2SystemEnv", Properties.class);
        method.setAccessible(true);
        System.setProperty("rocketmq.namesrv.domain", "value");
        method.invoke(null, properties);
        Assert.assertEquals("value", System.getProperty("rocketmq.namesrv.domain"));
    }

    @Test
    public void test() {


        LongAdder[] next = new LongAdder[13];
        for (int i = 0; i < next.length; i++) {
            next[i] = new LongAdder();
        }
        long currentTimeMillis = System.currentTimeMillis();
        long value = 3001;
        System.out.println(currentTimeMillis);
        final LongAdder[] times = next;

        if (null == times)
            return;

        // us
        if (value <= 0) {
            times[0].add(1);
        } else if (value < 10) {
            times[1].add(1);
        } else if (value < 50) {
            times[2].add(1);
        } else if (value < 100) {
            times[3].add(1);
        } else if (value < 200) {
            times[4].add(1);
        } else if (value < 500) {
            times[5].add(1);
        } else if (value < 1000) {
            times[6].add(1);
        }
        // 2s
        else if (value < 2000) {
            times[7].add(1);
        }
        // 3s
        else if (value < 3000) {
            times[8].add(1);
        }
        // 4s
        else if (value < 4000) {
            times[9].add(1);
        }
        // 5s
        else if (value < 5000) {
            times[10].add(1);
        }
        // 10s
        else if (value < 10000) {
            times[11].add(1);
        } else {
            times[12].add(1);
        }
        long endCurrentTimeMillis = System.currentTimeMillis();
        System.out.println(endCurrentTimeMillis);
        long endTime = endCurrentTimeMillis - currentTimeMillis;
        System.out.println(endTime);
    }
}