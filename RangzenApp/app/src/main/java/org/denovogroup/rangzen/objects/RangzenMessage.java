/*
 * Copyright (c) 2016, De Novo Group
 * All rights reserved.
 * 
 * Modernized for Android 14+ (2026) - Rangzen Revival Project
 */
package org.denovogroup.rangzen.objects;

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
    
    /** Whether this message has been read by the user */
    private boolean read;
    
    /** Number of hops this message has traveled */
    private int hopCount;
    
    /** Minimum mutual friends required to propagate (for restricted messages) */
    private int minContactsForHop;
    
    /** Message expiration time (Unix millis), 0 = never expires */
    private long expirationTime;

    /** Maximum message length in characters */
    public static final int MAX_MESSAGE_LENGTH = 140;
    
    /** Default trust score for new messages */
    public static final double DEFAULT_TRUST = 0.5;

    /**
     * Create a new message with default values.
     */
    public RangzenMessage() {
        this.messageId = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
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

    /**
     * Check if this message has expired.
     * 
     * @return true if expired, false otherwise
     */
    public boolean isExpired() {
        if (expirationTime == 0) {
            return false; // Never expires
        }
        return System.currentTimeMillis() > expirationTime;
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
