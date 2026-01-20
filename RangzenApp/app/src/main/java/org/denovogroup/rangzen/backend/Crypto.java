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
        new BigInteger("87A8E61DB4B6663CFFBBD19C651959998CEEF608660DD0F25D2CEED4435E3B00E00DF8F1D61957D4FAF7DF4561B2AA3016C3D91134096FAA3BF4296D830E9A7C20DED10D23B5FC32949DB30E80A380E84402692B2D6874A4614A9498595034D1372A2ABB589A5B379E2490432414AB21C649C47DEF5C82A3A03D2A09B10582D7ED82440951A99F4F6D03F421C041C8F5AD43FDC152E702D52EC5D4B2B9556F742855AB634FD22120B41ADF414E341F5A6515B45C0288E5B102B320721C4B974A676A8D569C01B08F9A24AD20FBEF63A6E35398604925A1D34A348B1B61BED5AF7571357D5D62DDF0D6FD9D0184A9563974C497A54C5535E49B64847D974B8C92F4316B715FE5D79E6940FA73D5426C0D28E0C4F41902E01B214BE8D5AF3304A161AE1D33A422D198E8439494341824AC913501255E6E6849892C9FB4DE9C2D3253AB3244F4692C69AE43B14BA82AAB98533D3004B60B08B5FED82B7B5476939C049EC2312D0A535132617997970425027598506243916694241031DECE809C3B002422955B65B546653E9AC16B47441B54B24855054D068F3E53063421046A34237142BC333E55AF80709C42589925238760033036F483421523317787401579E0537A7478332E07A250352A9886B86C201865643480B44670C86E55C6AADE599507E5211A34371A4815525592818301545B0A2092176A24513791337B6424D6638844391458746139384776644256653305888207551062941328974437502540706425946399990553", 16),
        new BigInteger("02", 16)
    );

    /** The default hash algorithm to use. */
    public static final String HASH_ALGORITHM = "SHA-1";

    /** Source of secure random bits. */
    public static final SecureRandom random = new SecureRandom();

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
            for (byte[] v : values) {
                md.reset();
                byte[] itemHash = md.digest(v);

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
                hashedBlindedItems.add(md.digest(i.toByteArray()));
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
                byte[] d = md.digest(i.toByteArray());
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
