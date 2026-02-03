/*
 * Copyright (c) 2016, De Novo Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * Modernized for Android 14+ (2026) - Rangzen Revival Project
 * Original iSEC-audited crypto logic preserved intact
 */
package org.denovogroup.rangzen.backend;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.InvalidParameterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import timber.log.Timber;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.KeyGenerationParameters;
import org.bouncycastle.crypto.generators.DHKeyPairGenerator;
import org.bouncycastle.crypto.params.DHKeyGenerationParameters;
import org.bouncycastle.crypto.params.DHParameters;
import org.bouncycastle.crypto.params.DHPublicKeyParameters;
import org.bouncycastle.crypto.params.DHPrivateKeyParameters;

/**
 * Cryptographic routines for Rangzen.
 *
 * Implements key generation and Private Set Intersection (PSI-Ca).
 *
 * Private Set Intersection based upon:
 * "Fast and Private Computation of Cardinality of Set Intersection and Union"
 * Cristofaro et al., CANS 2012, Springer.
 *
 * This code was security-audited by iSEC Partners in 2016.
 * Core cryptographic logic preserved from original Murmur implementation.
 */
public class Crypto {
    
    /** Initialize BouncyCastle security provider. */
    static {
        Security.insertProviderAt(new org.bouncycastle.jce.provider.BouncyCastleProvider(), 1);
    }
    
    private static final String TAG = "Crypto";

    /** Diffie-Hellman algorithm name string. */
    public static final String DH_ALGORITHM = "DH";

    /** Diffie-Hellman key size in bits. */
    public static final int DH_KEY_SIZE = 1024;

    /** Diffie-Hellman subgroup size in bits. */
    public static final int DH_SUBGROUP_SIZE = 160;

    /** Diffie-Hellman standard group parameters. */
    public static final DHParameters DH_GROUP_PARAMETERS = new DHParameters(
        // RFC 5114 1024-bit MODP group (P).
        new BigInteger(
            "B10B8F96A080E01DDE92DE5EAE5D54EC52C99FBCFB06A3C6"
                + "9A6A9DCA52D23B616073E28675A23D189838EF1E2EE652C0"
                + "13ECB4AEA906112324975C3CD49B83BFACCBDD7D90C4BD70"
                + "98488E9C219A73724EFFD6FAE5644738FAA31A4FF55BCCC0"
                + "A151AF5F0DC8B4BD45BF37DF365C1A65E68CFDA76D4DA708"
                + "DF1FB2BC2E4A4371",
            16
        ),
        // RFC 5114 generator (G).
        new BigInteger(
            "A4D1CBD5C3FD34126765A442EFB99905F8104DD258AC507F"
                + "D6406CFF14266D31266FEA1E5C41564B777E690F5504F213"
                + "160217B4B01B886A5E91547F9E2749F4D7FBD7D3B9A92EE1"
                + "909D0D2263F80A76A6A24C087A091F531DBF0A0169B6A28A"
                + "D662A4D18E73AFA32D779D5918D08BC8858F4DCEF97C2A24"
                + "855E6EEB22B3B2E5",
            16
        ),
        // RFC 5114 subgroup order (Q) needed for PSI modInverse.
        new BigInteger("F518AA8781A8DF278ABA4E7D64B7CB9D49462353", 16)
    );

    /** The default hash algorithm to use. */
    public static final String HASH_ALGORITHM = "SHA-1";

    /** Source of secure random bits. */
    public static final SecureRandom random = new SecureRandom();

    /** Canonical byte length for DH-1024 group values (1024 bits = 128 bytes). */
    private static final int CANONICAL_LENGTH = 128;

    /**
     * Convert BigInteger to canonical fixed-length byte array.
     * Ensures mathematically equal values always produce identical byte arrays,
     * regardless of BigInteger's internal representation (leading zeros, sign byte).
     *
     * This fixes a serialization bug where toByteArray() could produce different
     * byte representations for the same mathematical value, causing hash mismatches
     * in PSI-Ca comparisons.
     *
     * @param value The BigInteger to convert
     * @return A fixed-length byte array representation
     */
    private static byte[] toCanonicalBytes(BigInteger value) {
        byte[] raw = value.toByteArray();

        // Handle leading sign byte if present (BigInteger adds 0x00 for positive numbers
        // when the high bit would otherwise indicate negative)
        int offset = (raw.length > CANONICAL_LENGTH && raw[0] == 0) ? 1 : 0;
        int srcLen = raw.length - offset;

        byte[] canonical = new byte[CANONICAL_LENGTH];

        if (srcLen <= CANONICAL_LENGTH) {
            // Pad with leading zeros (right-align the value)
            System.arraycopy(raw, offset, canonical, CANONICAL_LENGTH - srcLen, srcLen);
        } else {
            // Truncate (shouldn't happen with proper DH values, but handle gracefully)
            System.arraycopy(raw, offset + srcLen - CANONICAL_LENGTH, canonical, 0, CANONICAL_LENGTH);
        }

        return canonical;
    }

    /**
     * Generates a Diffie-Hellman keypair of the default size.
     *
     * @return The generated Diffie-Hellman keypair or null upon failure.
     */
    protected static AsymmetricCipherKeyPair generateDHKeyPair() {
        try {
            KeyGenerationParameters kgp = new DHKeyGenerationParameters(random, DH_GROUP_PARAMETERS);
            DHKeyPairGenerator gen = new DHKeyPairGenerator();
            gen.init(kgp);
            return gen.generateKeyPair();
        } catch (InvalidParameterException e) {
            Timber.e(e, "InvalidParameterException while generating DH keypair");
        }
        return null;
    }

    /**
     * Extracts a byte array representation of a public key given a keypair.
     *
     * @param pubkey The keypair from which to get the public key.
     * @return The underlying byte array of the public key or null upon failure.
     */
    protected static byte[] encodeDHPublicKey(DHPublicKeyParameters pubkey) {
        return pubkey.getY().toByteArray();
    }

    /**
     * Extracts a byte array representation of a private key given a keypair.
     *
     * @param privkey The keypair from which to get the private key.
     * @return The underlying byte array of the private key or null upon failure.
     */
    protected static byte[] encodeDHPrivateKey(DHPrivateKeyParameters privkey) {
        return privkey.getX().toByteArray();
    }

    /**
     * Creates a public key object from the given encoded key.
     *
     * @param encoded The encoded key bytes to generate the key from.
     * @return The PublicKey object or null upon failure.
     */
    protected static DHPublicKeyParameters decodeDHPublicKey(byte[] encoded) {
        BigInteger i = new BigInteger(encoded);
        return new DHPublicKeyParameters(i, DH_GROUP_PARAMETERS);
    }

    /**
     * Creates a private key object from the given encoded key.
     *
     * @param encoded The encoded key bytes to generate the key from.
     * @return The PrivateKey object or null upon failure.
     */
    protected static DHPrivateKeyParameters decodeDHPrivateKey(byte[] encoded) {
        BigInteger i = new BigInteger(encoded);
        return new DHPrivateKeyParameters(i, DH_GROUP_PARAMETERS);
    }

    /**
     * Generates a user's long-term ID, which potentially has two parts (public/private).
     *
     * @return The keypair that represents the user's long-term ID.
     */
    public static AsymmetricCipherKeyPair generateUserID() {
        return generateDHKeyPair();
    }

    /**
     * Generates a public ID that can be shared with friends given a user's long-term ID.
     *
     * @param id The user ID represented as their key pair.
     * @return An encoded form of the public ID (public key) or null upon failure.
     */
    public static byte[] generatePublicID(AsymmetricCipherKeyPair id) {
        return encodeDHPublicKey((DHPublicKeyParameters) id.getPublic());
    }

    /**
     * Generates a private ID for the purposes of long term storage.
     *
     * @param id The user ID represented as their key pair.
     * @return An encoded form of the private ID (private key) or null upon failure.
     */
    public static byte[] generatePrivateID(AsymmetricCipherKeyPair id) {
        return encodeDHPrivateKey((DHPrivateKeyParameters) id.getPrivate());
    }

    /**
     * A data structure class for holding the private values needed on each side
     * of a private set intersection exchange.
     * 
     * This implements the PSI-Ca (Private Set Intersection with Cardinality) protocol
     * that allows two parties to compute how many friends they have in common
     * WITHOUT revealing WHO those friends are.
     */
    public static class PrivateSetIntersection {
        /** Our underlying private value. */
        private BigInteger x;

        /** The items that are to be intersected, shuffled and blinded by the key. */
        private ArrayList<BigInteger> blindedItems;

        /** The reply values from the "server" side, a tuple of byte arrays. */
        public static class ServerReplyTuple {
            /** Items shuffled/double blinded by the server. */
            public ArrayList<byte[]> doubleBlindedItems;

            /** The server's single-blinded items, shuffled and hashed. */
            public ArrayList<byte[]> hashedBlindedItems;

            public ServerReplyTuple(ArrayList<byte[]> doubleBlindedItems,
                                    ArrayList<byte[]> hashedBlindedItems) {
                this.doubleBlindedItems = doubleBlindedItems;
                this.hashedBlindedItems = hashedBlindedItems;
            }
        }

        /**
         * Generates an instance of one side of a PSI exchange given items to intersect.
         * Only stores hashes of the values given in a shuffled order.
         *
         * @param values A collection of items to intersect with the remote side.
         */
        public PrivateSetIntersection(ArrayList<byte[]> values) throws NoSuchAlgorithmException {
            this.blindedItems = new ArrayList<BigInteger>();

            // Pick a random value in the subgroup.
            BigInteger rand;
            do {
                rand = new BigInteger(DH_SUBGROUP_SIZE, random);
            } while (rand.equals(BigInteger.ZERO) || rand.equals(BigInteger.ONE));

            this.x = DH_GROUP_PARAMETERS.getG().modPow(rand, DH_GROUP_PARAMETERS.getP());

            MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
            int itemIdx = 0;
            for (byte[] v : values) {
                md.reset();
                // Canonicalize the input to handle variable-length BigInteger serialization.
                // Friend IDs may be 128 or 129 bytes due to BigInteger.toByteArray() adding
                // a leading 0x00 when the high bit is set. We normalize to ensure identical
                // inputs produce identical hashes regardless of leading zero padding.
                boolean needsCanon = v.length > CANONICAL_LENGTH;
                byte[] canonicalInput = needsCanon ? toCanonicalBytes(new BigInteger(1, v)) : v;

                byte[] itemHash = md.digest(canonicalInput);

                // Generate a positive BigInteger (signum == 1) from the bytes.
                BigInteger val = new BigInteger(1, itemHash);

                // Raise the group's generator to the hash value, to land on a value in the subgroup.
                BigInteger item = DH_GROUP_PARAMETERS.getG().modPow(val, DH_GROUP_PARAMETERS.getP());

                // Blind the item using the key.
                BigInteger blindedItem = item.modPow(x, DH_GROUP_PARAMETERS.getP());

                this.blindedItems.add(blindedItem);
            }

            // Securely shuffle the items.
            Collections.shuffle(this.blindedItems, random);
        }

        /**
         * Generates an encoded version of what the "client" sends to the "server" of the PSI.
         *
         * @return An ArrayList of byte[] values that represent the "client"'s blinded/encoded set.
         */
        public ArrayList<byte[]> encodeBlindedItems() {
            ArrayList<byte[]> r = new ArrayList<byte[]>(blindedItems.size());
            for (BigInteger i : blindedItems) {
                r.add(i.toByteArray());
            }
            return r;
        }

        /**
         * Takes an encoded collection of blinded items from the "client" and
         * generates two arrays, the first of double-blinded values and the second
         * of the "server"'s single-blinded values.
         *
         * @param remoteBlindedItems The values blinded by the remote side (the "client").
         * @return A tuple of the double blinded values and hashes of our blinded values.
         */
        public ServerReplyTuple replyToBlindedItems(
                ArrayList<byte[]> remoteBlindedItems) throws NoSuchAlgorithmException,
                IllegalArgumentException {

            if (remoteBlindedItems == null) {
                throw new IllegalArgumentException("Null remote blinded items to replyToBlindedItems!");
            }
            
            // Double blind all the values the other side sent by blinding them with our private value.
            ArrayList<byte[]> doubleBlindedItems = new ArrayList<byte[]>(remoteBlindedItems.size());
            for (byte[] b : remoteBlindedItems) {
                BigInteger i = new BigInteger(b);
                BigInteger iDoubleBlind = i.modPow(x, DH_GROUP_PARAMETERS.getP());
                doubleBlindedItems.add(iDoubleBlind.toByteArray());
            }

            java.util.Collections.shuffle(doubleBlindedItems, random);

            // Also generate hashes of our blinded values to send to the other side.
            MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
            ArrayList<byte[]> hashedBlindedItems = new ArrayList<byte[]>(blindedItems.size());
            for (BigInteger i : blindedItems) {
                md.reset();
                byte[] canonBytes = toCanonicalBytes(i);
                byte[] hash = md.digest(canonBytes);
                hashedBlindedItems.add(hash);
            }

            return new ServerReplyTuple(doubleBlindedItems, hashedBlindedItems);
        }

        /**
         * Calculates the set intersection cardinality given a "server" reply.
         *
         * @param reply A reply tuple from the "server".
         * @return The number of items that intersect between the "client" and "server" sets.
         */
        public int getCardinality(ServerReplyTuple reply) throws NoSuchAlgorithmException {
            // Store the "server"'s values in a HashSet so we can easily test whether
            // we have intersections.
            HashSet<ByteBuffer> serverHashedBlindedItems = new HashSet<ByteBuffer>();
            for (byte[] b : reply.hashedBlindedItems) {
                serverHashedBlindedItems.add(ByteBuffer.wrap(b));
            }

            // For each double blinded value, unblind one step and check for intersection.
            int cardinality = 0;
            MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
            for (byte[] b : reply.doubleBlindedItems) {
                md.reset();

                // Unblind the value.
                BigInteger iDoubleBlind = new BigInteger(b);

                BigInteger xInverse = x.modInverse(DH_GROUP_PARAMETERS.getQ());
                BigInteger i = iDoubleBlind.modPow(xInverse, DH_GROUP_PARAMETERS.getP());

                // Hash it.
                byte[] canonBytes = toCanonicalBytes(i);
                byte[] d = md.digest(canonBytes);
                ByteBuffer buf = ByteBuffer.wrap(d);

                // Check if it's in the set.
                if (serverHashedBlindedItems.contains(buf)) {
                    cardinality++;
                }
            }
            return cardinality;
        }
    }

    /**
     * Converts an ArrayList of byte[] to an ArrayList of byte[] (utility for compatibility).
     * If the input is an empty list, the output is an empty list.
     * If the input is null, the output is null.
     *
     * @return A copy of the list, or null if input was null.
     */
    public static ArrayList<byte[]> copyByteArrayList(ArrayList<byte[]> byteArrays) {
        if (byteArrays == null) {
            return null;
        }
        return new ArrayList<>(byteArrays);
    }

    /**
     * Encodes a string to its hash representation.
     *
     * @param input The string to encode
     * @return The hash of the input string, or null on failure
     */
    public static byte[] encodeString(String input) {
        if (input == null) return null;

        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
            md.reset();
            return md.digest(input.getBytes());
        } catch (NoSuchAlgorithmException e) {
            Timber.e(e, "Hash algorithm not found");
        }

        return null;
    }
}
