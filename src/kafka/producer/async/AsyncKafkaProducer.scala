/*
 * Copyright 2010 LinkedIn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.producer.async

import java.util.concurrent.LinkedBlockingQueue
import kafka.utils.Utils
import java.util.concurrent.atomic.AtomicBoolean
import kafka.serializer.SerDeser
import org.apache.log4j.{Level, Logger}
import kafka.producer.SimpleProducer

object AsyncKafkaProducer {
  val shutdown = new Object
}

class AsyncKafkaProducer[T](config: AsyncProducerConfig,
                            producer: SimpleProducer,
                            serializer: SerDeser[T]) {
  private val logger = Logger.getLogger(classOf[AsyncKafkaProducer[T]])
  private val closed = new AtomicBoolean(false)
  private val queue = new LinkedBlockingQueue[QueueItem[T]](config.queueSize)
  private val handler = new EventHandler[T](producer, serializer)
  private val sendThread = new ProducerSendThread(queue, serializer, handler,
    config.queueTime, config.batchSize, AsyncKafkaProducer.shutdown)
  sendThread.setDaemon(false)

  def this(config: AsyncProducerConfig) {
    this(config,
      new SimpleProducer(config.host, config.port, config.bufferSize, config.connectTimeoutMs,config.reconnectInterval),
      Utils.getObject(config.serializerClass))
  }

  def start = sendThread.start

  def send(event: T) {
    if(closed.get)
      throw new QueueClosedException("Attempt to add event to a closed queue.")

    val getTopic: (T) => String = serializer.getTopic
    val added = queue.offer(new QueueItem(event, getTopic))

    if(!added) {
      logger.error("Event queue is full of unsent messages, could not send event: " + event.toString)
      throw new QueueFullException("Event queue is full of unsent messages, could not send event: " + event.toString)
    }
  }

  def send(event: T, topicSerializer: Function1[T, String]) {
    if(closed.get)
      throw new QueueClosedException("Attempt to add event to a closed queue.")

    val added = queue.offer(new QueueItem(event, topicSerializer))

    if(!added) {
      logger.error("Event queue is full of unsent messages, could not send event: " + event.toString)
      throw new QueueFullException("Event queue is full of unsent messages, could not send event: " + event.toString)
    }
  }

  def close = {
    queue.put(new QueueItem(AsyncKafkaProducer.shutdown.asInstanceOf[T], null))
    sendThread.join(3000)
    sendThread.shutdown
    closed.set(true)
  }

  // for testing only
  def setLoggerLevel(level: Level) = logger.setLevel(level)
}

class QueueItem[T](data: T, topicCbk: Function1[T, String]) {
  def getData: T = data
  def getTopicCallback:Function1[T, String] = topicCbk
}
