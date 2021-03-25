package org.yggdrasil.node.network.messages.payloads;

import org.apache.commons.lang3.SerializationUtils;
import org.yggdrasil.node.network.messages.MessagePayload;

/**
 * The Ping Pong Message is used to communicate back and forth to see if a conneciton
 * is alive between nodes.
 *
 * @since 0.0.1
 * @author nathanielbunch
 */
public class PingPongMessage implements MessagePayload {

    private final int nonce;

    private PingPongMessage(Builder builder) {
        this.nonce = builder.nonce;
    }

    public int getNonce() {
        return nonce;
    }

    @Override
    public byte[] getDataBytes() {
        return SerializationUtils.serialize(nonce);
    }

    public static class Builder {

        private int nonce;

        private Builder(){}

        public static Builder newBuilder() {
            return new Builder();
        }

        public Builder setNonce(int nonce) {
            this.nonce = nonce;
            return this;
        }

        public PingPongMessage build() {
            return new PingPongMessage(this);
        }

    }
}