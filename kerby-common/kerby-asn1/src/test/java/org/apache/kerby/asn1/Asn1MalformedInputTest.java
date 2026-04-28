/**
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.kerby.asn1;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class Asn1MalformedInputTest {

    @Test
    public void testParseTruncatedLongFormLengthThrowsIoException() {
        byte[] truncatedLength = new byte[] {
            0x30, (byte) 0x82, 0x01
        };

        assertThatThrownBy(() -> Asn1.parse(truncatedLength))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Unexpected end of ASN.1 data");
    }

    @Test
    public void testDecodeOversizedPrimitiveLengthThrowsIoException() {
        byte[] oversizedOctetString = new byte[] {
            0x04, (byte) 0x84, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff
        };

        assertThatThrownBy(() -> Asn1.decode(oversizedOctetString))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("extends beyond available data");
    }

    @Test
    public void testParseIndefiniteLengthContainerWithoutEocThrowsIoException() {
        byte[] missingEoc = new byte[] {
            0x30, (byte) 0x80,
            0x02, 0x01, 0x05
        };

        assertThatThrownBy(() -> Asn1.parse(missingEoc))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("missing EOC terminator");
    }
}