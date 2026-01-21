/*
 * Copyright (c) 2016, De Novo Group
 * All rights reserved.
 * 
 * Modernized for Android 14+ (2026) - Rangzen Revival Project
 */
package org.denovogroup.rangzen.objects;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;
import java.util.UUID;

/**
 * Represents a message in the Rangzen network.
 * Messages propagate through the delay-tolerant mesh network
 * and are prioritized based on trust scores from the PSI-Ca protocol.
 */
public class RangzenMessage {
    
    /** Unique identifier for this message */
    private String messageId;
    
    /** The actual message content (limited to 140 chars for efficiency) */
    private String text;
    
    /** Trust/connection score - higher means more trusted source */
    private double trustScore;
    
    /** Number of endorsements (likes) this message has received */
    private int likes;
    
    /** Whether the current user has endorsed this message */
    private boolean liked;
    
    /** Anonymous pseudonym of the sender */
    private String pseudonym;
    
    /** Timestamp when message was created (Unix millis) */
    private long timestamp;

    /** Timestamp when message was received/stored locally (Unix millis) */
    private long receivedTimestamp;
    
    /** Whether this message has been read by the user */
    private boolean read;
    
    /** Number of hops this message has traveled */
    private int hopCount;
    
    /** Minimum mutual friends required to propagate (for restricted messages) */
    private int minContactsForHop;
    
    /** Message expiration time (Unix millis), 0 = never expires */
    private long expirationTime;

    /** Optional location in "lat lng" format */
    private String latLong;

    /** Optional parent message id */
    private String parentId;

    /** Optional big parent message id */
    private String bigParentId;

    /** Maximum message length in characters */
    public static final int MAX_MESSAGE_LENGTH = 140;
    
    /** Default trust score for new messages */
    public static final double DEFAULT_TRUST = 0.5;

    /** Legacy protocol defaults */
    public static final double LEGACY_DEFAULT_TRUST = 0.01d;

    /** Legacy JSON keys (from the original Rangzen/Murmur protocol) */
    public static final String MESSAGE_ID_KEY = "messageId";
    public static final String TEXT_KEY = "text";
    public static final String TRUST_KEY = "trust";
    public static final String PRIORITY_KEY = "priority";
    public static final String PSEUDONYM_KEY = "pseudonym";
    public static final String LATLONG_KEY = "latlang";
    public static final String TIMEBOUND_KEY = "timebound";
    public static final String PARENT_KEY = "parent";
    public static final String BIGPARENT_KEY = "bigparent";
    public static final String HOP_KEY = "hop";
    public static final String MIN_USERS_P_HOP_KEY = "min_users_p_hop";

    /** Random source for legacy trust noise */
    private static final Random TRUST_RANDOM = new Random();

    /**
     * Create a new message with default values.
     */
    public RangzenMessage() {
        this.messageId = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        // Initialize to zero; set when storing in the local DB.
        this.receivedTimestamp = 0L;
        this.trustScore = DEFAULT_TRUST;
        this.hopCount = 0;
        this.minContactsForHop = 0;
        this.read = false;
        this.liked = false;
        this.likes = 0;
    }

    /**
     * Create a new message with specified text.
     * 
     * @param text The message content
     * @param pseudonym The sender's anonymous pseudonym
     */
    public RangzenMessage(String text, String pseudonym) {
        this();
        setText(text);
        this.pseudonym = pseudonym;
    }

    // Getters and setters

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        // Enforce maximum length
        if (text != null && text.length() > MAX_MESSAGE_LENGTH) {
            this.text = text.substring(0, MAX_MESSAGE_LENGTH);
        } else {
            this.text = text;
        }
    }

    public double getTrustScore() {
        return trustScore;
    }

    public void setTrustScore(double trustScore) {
        // Clamp to valid range [0, 1]
        this.trustScore = Math.max(0.0, Math.min(1.0, trustScore));
    }

    public int getLikes() {
        return likes;
    }

    public void setLikes(int likes) {
        this.likes = Math.max(0, likes);
    }

    public boolean isLiked() {
        return liked;
    }

    public void setLiked(boolean liked) {
        this.liked = liked;
    }

    public String getPseudonym() {
        return pseudonym;
    }

    public void setPseudonym(String pseudonym) {
        this.pseudonym = pseudonym;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getReceivedTimestamp() {
        return receivedTimestamp;
    }

    public void setReceivedTimestamp(long receivedTimestamp) {
        // Store the local receipt time separately from the composed time.
        this.receivedTimestamp = receivedTimestamp;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public int getHopCount() {
        return hopCount;
    }

    public void setHopCount(int hopCount) {
        this.hopCount = Math.max(0, hopCount);
    }
    
    public void incrementHopCount() {
        this.hopCount++;
    }

    public int getMinContactsForHop() {
        return minContactsForHop;
    }

    public void setMinContactsForHop(int minContactsForHop) {
        this.minContactsForHop = Math.max(0, minContactsForHop);
    }

    public long getExpirationTime() {
        return expirationTime;
    }

    public void setExpirationTime(long expirationTime) {
        this.expirationTime = expirationTime;
    }

    public String getLatLong() {
        return latLong;
    }

    public void setLatLong(String latLong) {
        this.latLong = latLong;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getBigParentId() {
        return bigParentId;
    }

    public void setBigParentId(String bigParentId) {
        this.bigParentId = bigParentId;
    }

    /**
     * Check if this message has expired.
     * 
     * @return true if expired, false otherwise
     */
    public boolean isExpired() {
        // Treat zero as "never expires".
        if (expirationTime == 0) {
            return false; // Never expires
        }
        // Expiration time is stored as a duration (legacy behavior).
        long expiryAt = timestamp + expirationTime;
        // Message is expired when now exceeds timestamp + duration.
        return System.currentTimeMillis() > expiryAt;
    }

    /**
     * Calculate the priority of this message for propagation.
     * Higher priority messages are sent first when bandwidth is limited.
     * 
     * Priority is based on:
     * - Trust score (higher = more priority)
     * - Recency (newer = more priority)
     * - Endorsements (more = more priority)
     * 
     * @return Priority value between 0 and 1
     */
    public double calculatePriority() {
        // Weight factors
        final double TRUST_WEIGHT = 0.5;
        final double RECENCY_WEIGHT = 0.3;
        final double LIKES_WEIGHT = 0.2;
        
        // Trust component (already 0-1)
        double trustComponent = trustScore * TRUST_WEIGHT;
        
        // Recency component - messages from last hour get full score
        long ageMillis = System.currentTimeMillis() - timestamp;
        long hourMillis = 60 * 60 * 1000;
        double recencyComponent = Math.max(0, 1.0 - (double) ageMillis / (24 * hourMillis)) * RECENCY_WEIGHT;
        
        // Likes component - logarithmic scale
        double likesComponent = Math.min(1.0, Math.log10(likes + 1) / 3) * LIKES_WEIGHT;
        
        return trustComponent + recencyComponent + likesComponent;
    }

    /**
     * Convert this message to the legacy JSON format used by the original Rangzen protocol.
     */
    public JSONObject toLegacyJson() {
        return toLegacyJson(true, true, true, 0.0);
    }

    /**
     * Convert this message to the legacy JSON format used by the original Rangzen protocol.
     *
     * @param includePseudonym include the pseudonym field when available
     * @param shareLocation include the location field when available
     * @param includeTrust include the trust field when enabled
     * @param trustNoiseVariance variance for Gaussian noise added to trust (0 for none)
     */
    public JSONObject toLegacyJson(boolean includePseudonym,
                                   boolean shareLocation,
                                   boolean includeTrust,
                                   double trustNoiseVariance) {
        JSONObject result = new JSONObject();
        try {
            // Required fields.
            result.put(MESSAGE_ID_KEY, messageId);
            result.put(TEXT_KEY, text);
            result.put(PRIORITY_KEY, likes);
            result.put(HOP_KEY, hopCount + 1);
            result.put(MIN_USERS_P_HOP_KEY, minContactsForHop);
            // Optional parent linkage.
            if (parentId != null && !parentId.isEmpty()) {
                result.put(PARENT_KEY, parentId);
            }
            if (bigParentId != null && !bigParentId.isEmpty()) {
                result.put(BIGPARENT_KEY, bigParentId);
            }
            // Optional timebound expiry.
            if (expirationTime > 0) {
                result.put(TIMEBOUND_KEY, expirationTime);
            }
            // Optional pseudonym.
            if (includePseudonym && pseudonym != null && !pseudonym.isEmpty()) {
                result.put(PSEUDONYM_KEY, pseudonym);
            }
            // Optional location.
            if (shareLocation && latLong != null && !latLong.isEmpty()) {
                result.put(LATLONG_KEY, latLong);
            }
            // Optional trust with noise.
            if (includeTrust) {
                double noisyTrust = trustScore + legacyTrustNoise(trustNoiseVariance);
                result.put(TRUST_KEY, noisyTrust);
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to serialize RangzenMessage to legacy JSON", e);
        }
        return result;
    }

    /**
     * Generate Gaussian noise for legacy trust values.
     */
    private static double legacyTrustNoise(double variance) {
        if (variance <= 0.0) {
            return 0.0;
        }
        return TRUST_RANDOM.nextGaussian() * Math.sqrt(variance);
    }

    /**
     * Build a RangzenMessage from the legacy JSON format.
     */
    public static RangzenMessage fromLegacyJson(JSONObject json) {
        RangzenMessage message = new RangzenMessage();
        String messageId = json.optString(MESSAGE_ID_KEY, null);
        String text = json.optString(TEXT_KEY, null);
        if (messageId == null || text == null) {
            throw new IllegalArgumentException("Legacy JSON missing messageId or text");
        }
        message.setMessageId(messageId);
        message.setText(text);
        message.setTrustScore(json.optDouble(TRUST_KEY, LEGACY_DEFAULT_TRUST));
        message.setLikes(json.optInt(PRIORITY_KEY, 0));
        message.setPseudonym(json.optString(PSEUDONYM_KEY, null));
        message.setLatLong(json.optString(LATLONG_KEY, null));
        message.setExpirationTime(json.optLong(TIMEBOUND_KEY, 0L));
        message.setParentId(json.optString(PARENT_KEY, null));
        message.setBigParentId(json.optString(BIGPARENT_KEY, null));
        message.setHopCount(json.optInt(HOP_KEY, 0));
        message.setMinContactsForHop(json.optInt(MIN_USERS_P_HOP_KEY, 0));
        message.setTimestamp(System.currentTimeMillis());
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RangzenMessage that = (RangzenMessage) o;
        return messageId != null && messageId.equals(that.messageId);
    }

    @Override
    public int hashCode() {
        return messageId != null ? messageId.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "RangzenMessage{" +
                "id='" + messageId + '\'' +
                ", text='" + (text != null ? text.substring(0, Math.min(20, text.length())) + "..." : "null") + '\'' +
                ", trust=" + trustScore +
                ", hops=" + hopCount +
                '}';
    }
}
