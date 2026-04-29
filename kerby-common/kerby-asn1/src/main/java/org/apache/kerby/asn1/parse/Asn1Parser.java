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
package org.apache.kerby.asn1.parse;

import org.apache.kerby.asn1.Tag;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * ASN1 parser.
 */
public class Asn1Parser {

    private static final String MAX_NESTING_DEPTH_PROPERTY = "org.apache.kerby.asn1.maxNestingDepth";
    private static final int DEFAULT_MAX_NESTING_DEPTH = 1024;
    private static final int MAX_NESTING_DEPTH = resolveMaxNestingDepth();

    public static void parse(Asn1Container container) throws IOException {
        parse(container, 0);
    }

    private static void parse(Asn1Container container, int depth) throws IOException {
        validateDepth(depth);
        Asn1Reader reader = new Asn1Reader(container.getBuffer());
        int pos = container.getBodyStart();
        boolean sawEoc = false;
        while (true) {
            reader.setPosition(pos);
            Asn1ParseResult asn1Obj = parse(reader, depth);
            if (asn1Obj == null) {
                break;
            }

            container.addItem(asn1Obj);

            pos += asn1Obj.getEncodingLength();
            if (asn1Obj.isEOC()) {
                sawEoc = true;
                break;
            }

            if (container.checkBodyFinished(pos)) {
                break;
            }
        }

        if (!container.isDefinitiveLength() && !sawEoc) {
            throw new IOException("Indefinite length ASN.1 container missing EOC terminator");
        }

        container.setBodyEnd(pos);
    }

    public static Asn1ParseResult parse(ByteBuffer content) throws IOException {
        Asn1Reader reader = new Asn1Reader(content);
        return parse(reader, 0);
    }

    public static Asn1ParseResult parse(Asn1Reader reader) throws IOException {
        return parse(reader, 0);
    }

    private static Asn1ParseResult parse(Asn1Reader reader, int depth) throws IOException {
        validateDepth(depth);
        if (!reader.available()) {
            return null;
        }

        Asn1Header header = reader.readHeader();
        Tag tmpTag = header.getTag();
        int bodyStart = reader.getPosition();
        validateLength(header, tmpTag, bodyStart, reader.getBuffer());
        Asn1ParseResult parseResult;

        if (tmpTag.isPrimitive()) {
            parseResult = new Asn1Item(header, bodyStart, reader.getBuffer());
        } else {
            Asn1Container container = new Asn1Container(header,
                bodyStart, reader.getBuffer());
            if (header.getLength() != 0) {
                parse(container, depth + 1);
            }
            parseResult = container;
        }

        return parseResult;
    }

    private static void validateLength(Asn1Header header, Tag tag,
                                       int bodyStart, ByteBuffer buffer) throws IOException {
        if (!header.isDefinitiveLength()) {
            if (tag.isPrimitive()) {
                throw new IOException("Primitive ASN.1 value cannot use indefinite length");
            }
            return;
        }

        long bodyEnd = (long) bodyStart + header.getLength();
        if (bodyEnd > buffer.limit()) {
            throw new IOException("ASN.1 length extends beyond available data: " + header.getLength());
        }
    }

    private static void validateDepth(int depth) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IOException("ASN.1 nesting depth exceeds maximum allowed depth: " + MAX_NESTING_DEPTH);
        }
    }

    private static int resolveMaxNestingDepth() {
        String configuredValue;
        try {
            configuredValue = System.getProperty(MAX_NESTING_DEPTH_PROPERTY);
        } catch (SecurityException e) {
            return DEFAULT_MAX_NESTING_DEPTH;
        }

        if (configuredValue == null) {
            return DEFAULT_MAX_NESTING_DEPTH;
        }

        try {
            int configuredDepth = Integer.parseInt(configuredValue.trim());
            if (configuredDepth > 0) {
                return configuredDepth;
            }
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_NESTING_DEPTH;
        }

        return DEFAULT_MAX_NESTING_DEPTH;
    }
}
