package io.github.zhouhuimin.flashsale.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhouhuimin.flashsale.order.OrderConsumer;
import io.github.zhouhuimin.flashsale.order.OrderEvent;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class RocketMqLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RocketMqLifecycle.class);
    private final DefaultMQProducer producer = new DefaultMQProducer("flashsale-outbox-producers");
    private final DefaultMQPushConsumer consumer;
    private final ObjectMapper json;
    private final OrderConsumer handler;
    private final String topic;
    private volatile boolean running;
    public RocketMqLifecycle(@Value("${app.mq.nameserver}") String nameserver, @Value("${app.mq.topic}") String topic,
        @Value("${app.mq.consumer-group}") String group, ObjectMapper json, OrderConsumer handler) {
        this.topic=topic; this.json=json; this.handler=handler;
        producer.setNamesrvAddr(nameserver); producer.setSendMsgTimeout(3000); producer.setRetryTimesWhenSendFailed(2);
        producer.setInstanceName(java.util.UUID.randomUUID().toString());
        consumer = new DefaultMQPushConsumer(group); consumer.setNamesrvAddr(nameserver);
        consumer.setInstanceName(java.util.UUID.randomUUID().toString());
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeThreadMin(8); consumer.setConsumeThreadMax(16); consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setMaxReconsumeTimes(16);
    }
    @Override public void start() {
        try {
            producer.start(); consumer.subscribe(topic, "*");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (var msg : messages) {
                    try { handler.consume(json.readValue(msg.getBody(), OrderEvent.class)); }
                    catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        log.error("Order message failed; key={}, retry={}", msg.getKeys(), msg.getReconsumeTimes(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start(); running=true;
        } catch (Exception e) { consumer.shutdown(); producer.shutdown(); throw new IllegalStateException("RocketMQ startup failed", e); }
    }
    public void send(OrderEvent event) throws Exception {
        var message = new Message(topic, "created", event.orderId(), json.writeValueAsBytes(event));
        if (producer.send(message).getSendStatus() != SendStatus.SEND_OK) throw new IllegalStateException("Broker has not confirmed durable send");
    }
    @Override public void stop() { running=false; consumer.shutdown(); producer.shutdown(); }
    @Override public boolean isRunning() { return running; }
}
