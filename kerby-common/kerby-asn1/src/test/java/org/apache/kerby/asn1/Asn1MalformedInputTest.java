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

    @Test
    public void testParseDeepNestingShouldFailFastWithIoExceptionAfterDepthLimitFix() {
        byte[] deeplyNested = buildDeeplyNestedIndefiniteSequence(10000);

        assertThatThrownBy(() -> Asn1.parse(deeplyNested))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("depth")
            .hasMessageContaining("maximum allowed");
    }

    @Test
    public void testParseChildPrimitiveLengthEscapesParentBoundaryShouldThrowIoException() {
        byte[] escapedChild = new byte[] {
            0x30, 0x03,
            0x04, 0x05,
            0x41, 0x41, 0x41, 0x41, 0x41
        };

        assertThatThrownBy(() -> Asn1.parse(escapedChild))
            .isInstanceOf(IOException.class);
    }

    @Test
    public void testParseChildConstructedLengthEscapesParentBoundaryShouldThrowIoException() {
        byte[] escapedConstructedChild = new byte[] {
            0x30, 0x04,
            0x30, 0x06,
            0x02, 0x01, 0x01,
            0x02, 0x01, 0x01
        };

        assertThatThrownBy(() -> Asn1.parse(escapedConstructedChild))
            .isInstanceOf(IOException.class);
    }

    @Test
    public void testParseHighTagWithUnterminatedContinuationShouldThrowIoException()  {
        byte[] malformedHighTag = new byte[] {
            0x1f,
            (byte) 0x81,
            (byte) 0x80,
            0x00
        };

        assertThatThrownBy(() -> Asn1.parse(malformedHighTag))
            .isInstanceOf(IOException.class);
    }

    @Test
    public void testParseHighTagWithLongContinuationChainShouldThrowIoException() {
        byte[] malformedHighTag = new byte[] {
            0x1f,
            (byte) 0x81,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            0x01,
            0x00
        };

        assertThatThrownBy(() -> Asn1.parse(malformedHighTag))
            .isInstanceOf(IOException.class);
    }

    @Test
    public void testParseEocInsideDefiniteContainerShouldThrowIoException() {
        byte[] definiteWithEmbeddedEoc = new byte[] {
            0x30, 0x05,
            0x00, 0x00,
            0x02, 0x01, 0x01
        };

        assertThatThrownBy(() -> Asn1.parse(definiteWithEmbeddedEoc))
            .isInstanceOf(IOException.class);
    }

    @Test
    public void testParseDefiniteContainerMustNotUseEocAsTerminator() {
        byte[] definiteWithEarlyEoc = new byte[] {
            0x30, 0x08,
            0x30, 0x03,
            0x02, 0x01, 0x01,
            0x00, 0x00,
            0x05
        };

        assertThatThrownBy(() -> Asn1.parse(definiteWithEarlyEoc))
            .isInstanceOf(IOException.class);
    }

    private static byte[] buildDeeplyNestedIndefiniteSequence(int depth) {
        byte[] integerOne = new byte[] {0x02, 0x01, 0x01};
        byte[] encoded = new byte[depth * 4 + integerOne.length];
        int pos = 0;

        for (int i = 0; i < depth; i++) {
            encoded[pos++] = 0x30;
            encoded[pos++] = (byte) 0x80;
        }

        System.arraycopy(integerOne, 0, encoded, pos, integerOne.length);
        pos += integerOne.length;

        for (int i = 0; i < depth; i++) {
            encoded[pos++] = 0x00;
            encoded[pos++] = 0x00;
        }

        return encoded;
    }
}
