package dev.portfolio.verifyflow.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queue topology for the reconciliation step.
 *
 * <p>The dead-letter queue is declared here rather than left implicit because a task that fails four
 * times is not noise to be dropped - it is a verification that will never resolve without someone
 * looking. Nothing auto-deletes.
 */
@Configuration
public class RabbitConfig {

  private final VerifyFlowProperties props;

  public RabbitConfig(VerifyFlowProperties props) {
    this.props = props;
  }

  @Bean
  public DirectExchange reconcileExchange() {
    return new DirectExchange(props.reconcile().exchange(), true, false);
  }

  @Bean
  public Queue reconcileQueue() {
    return QueueBuilder.durable(props.reconcile().queue())
        .deadLetterExchange("")
        .deadLetterRoutingKey(props.reconcile().dlq())
        .build();
  }

  @Bean
  public Queue reconcileDlq() {
    return QueueBuilder.durable(props.reconcile().dlq()).build();
  }

  @Bean
  public Binding reconcileBinding() {
    return BindingBuilder.bind(reconcileQueue())
        .to(reconcileExchange())
        .with(props.reconcile().routingKey());
  }

  @Bean
  public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  @Bean
  public RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter converter) {
    RabbitTemplate t = new RabbitTemplate(cf);
    t.setMessageConverter(converter);
    return t;
  }
}
