package zio.kafka.consumer

import net.manub.embeddedkafka.EmbeddedKafka
import org.apache.kafka.common.TopicPartition
import zio.{ Promise, Ref, Task, ZIO }
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._
import zio.kafka.consumer.Consumer.OffsetRetrieval
import zio.kafka.KafkaTestUtils._
import zio.kafka.consumer.diagnostics.{ DiagnosticEvent, Diagnostics }
import zio.kafka.embedded.Kafka
import zio.stream.{ ZSink, ZStream }
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test.environment._
import zio.test.{ DefaultRunnableSpec, _ }

object ConsumerSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Throwable] =
    suite("Consumer Streaming")(
      testM("plainStream emits messages for a topic subscription") {
        val kvs = (1 to 5).toList.map(i => (s"key$i", s"msg$i"))
        for {
          _ <- produceMany("topic150", kvs)

          records <- Consumer
                      .subscribeAnd[Any, String, String](Subscription.Topics(Set("topic150")))
                      .plainStream
                      .flattenChunks
                      .take(5)
                      .runCollect
                      .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer("group150", "client150"))
          kvOut = records.map { r =>
            (r.record.key, r.record.value)
          }
        } yield assert(kvOut)(equalTo(kvs))
      },
      testM("Consumer.subscribeAnd works properly") {
        val kvs = (1 to 5).toList.map(i => (s"key$i", s"msg$i"))
        for {
          _ <- produceMany("topic160", kvs)

          records <- Consumer
                      .subscribeAnd[Any, String, String](Subscription.Topics(Set("topic160")))
                      .plainStream
                      .flattenChunks
                      .take(5)
                      .runCollect
                      .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer("group160", "client160"))
          kvOut = records.map { r =>
            (r.record.key, r.record.value)
          }
        } yield assert(kvOut)(equalTo(kvs))
      },
      testM("Consuming+provideCustomLayer") {
        val kvs = (1 to 10000).toList.map(i => (s"key$i", s"msg$i"))
        for {
          _ <- produceMany("topic170", kvs)

          records <- Consumer
                      .subscribeAnd[Any, String, String](Subscription.Topics(Set("topic170")))
                      .plainStream
                      .flattenChunks
                      .take(10000)
                      .runCollect
                      .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer("group170", "client170"))
          kvOut = records.map { r =>
            (r.record.key, r.record.value)
          }
        } yield assert(kvOut)(equalTo(kvs))
      },
      testM("plainStream emits messages for a pattern subscription") {
        val kvs = (1 to 5).toList.map(i => (s"key$i", s"msg$i"))
        for {
          _ <- produceMany("pattern150", kvs)
          records <- Consumer
                      .subscribeAnd[Any, String, String](Subscription.Pattern("pattern[0-9]+".r))
                      .plainStream
                      .flattenChunks
                      .take(5)
                      .runCollect
                      .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer("group150", "client150"))
          kvOut = records.map { r =>
            (r.record.key, r.record.value)
          }
        } yield assert(kvOut)(equalTo(kvs))
      },
      testM("receive only messages from the subscribed topic-partition when creating a manual subscription") {
        val nrPartitions = 5
        val topic        = "manual-topic"

        for {
          _ <- ZIO.effectTotal(EmbeddedKafka.createCustomTopic(topic, partitions = nrPartitions))
          _ <- ZIO.foreach(1 to nrPartitions) { i =>
                produceMany(topic, partition = i % nrPartitions, kvs = List(s"key$i" -> s"msg$i"))
              }
          record <- Consumer
                     .subscribeAnd[Any, String, String](Subscription.manual(topic, partition = 2))
                     .plainStream
                     .flattenChunks
                     .take(1)
                     .runHead
                     .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer("group150", "client150"))
          kvOut = record.map(r => (r.record.key, r.record.value))
        } yield assert(kvOut)(isSome(equalTo("key2" -> "msg2")))
      },
      testM("receive from the right offset when creating a manual subscription with manual seeking") {
        val nrPartitions = 5
        val topic        = "manual-topic"

        val manualOffsetSeek = 3

        for {
          _ <- ZIO.effectTotal(EmbeddedKafka.createCustomTopic(topic, partitions = nrPartitions))
          _ <- ZIO.foreach(1 to nrPartitions) { i =>
                produceMany(topic, partition = i % nrPartitions, kvs = (1 to 10).map(j => s"key$i-$j" -> s"msg$i-$j"))
              }
          offsetRetrieval = OffsetRetrieval.Manual(tps => ZIO(tps.map(_ -> manualOffsetSeek.toLong).toMap))
          record <- Consumer
                     .subscribeAnd[Any, String, String](Subscription.manual(topic, partition = 2))
                     .plainStream
                     .flattenChunks
                     .take(1)
                     .runHead
                     .provideSomeLayer[Kafka with Blocking with Clock](
                       stringConsumer("group150", "client150", offsetRetrieval)
                     )
          kvOut = record.map(r => (r.record.key, r.record.value))
        } yield assert(kvOut)(isSome(equalTo("key2-3" -> "msg2-3")))
      },
      testM("restart from the committed position") {
        val data = (1 to 10).toList.map(i => s"key$i" -> s"msg$i")
        for {
          _ <- produceMany("topic1", 0, data)
          firstResults <- for {
                           results <- Consumer
                                       .subscribeAnd[Any, String, String](Subscription.Topics(Set("topic1")))
                                       .partitionedStream
                                       .filter(_._1 == new TopicPartition("topic1", 0))
                                       .flatMap(_._2.flattenChunks)
                                       .take(5)
                                       .transduce(ZSink.collectAll[CommittableRecord[String, String]])
                                       .mapConcatM { committableRecords =>
                                         val records = committableRecords.map(_.record)
                                         val offsetBatch =
                                           committableRecords.foldLeft(OffsetBatch.empty)(_ merge _.offset)

                                         offsetBatch.commit.as(records)
                                       }
                                       .runCollect
                                       .provideSomeLayer[Kafka with Blocking with Clock](
                                         stringConsumer("group1", "first")
                                       )
                         } yield results
          secondResults <- for {
                            results <- Consumer
                                        .subscribeAnd[Any, String, String](Subscription.Topics(Set("topic1")))
                                        .partitionedStream
                                        .flatMap(_._2.flattenChunks)
                                        .take(5)
                                        .transduce(ZSink.collectAll[CommittableRecord[String, String]])
                                        .mapConcatM { committableRecords =>
                                          val records = committableRecords.map(_.record)
                                          val offsetBatch =
                                            committableRecords.foldLeft(OffsetBatch.empty)(_ merge _.offset)

                                          offsetBatch.commit.as(records)
                                        }
                                        .runCollect
                                        .provideSomeLayer[Kafka with Blocking with Clock](
                                          stringConsumer("group1", "second")
                                        )
                          } yield results
        } yield assert((firstResults ++ secondResults).map(rec => rec.key() -> rec.value()))(equalTo(data))
      },
      testM("partitionedStream emits messages for each partition in a separate stream") {
        val nrMessages   = 50
        val nrPartitions = 5

        for {
          // Produce messages on several partitions
          topic <- randomTopic
          group <- randomGroup
          _     <- Task(EmbeddedKafka.createCustomTopic(topic, partitions = nrPartitions))
          _ <- ZIO.foreach(1 to nrMessages) { i =>
                produceMany(topic, partition = i % nrPartitions, kvs = List(s"key$i" -> s"msg$i"))
              }

          // Consume messages
          messagesReceived <- ZIO.foreach(0 until nrPartitions)(i => Ref.make[Int](0).map(i -> _)).map(_.toMap)
          subscription     = Subscription.topics(topic)
          fib <- Consumer
                  .subscribeAnd[Any, String, String](subscription)
                  .partitionedStream
                  .flatMapPar(nrPartitions) {
                    case (_, partition) =>
                      partition.mapM { record =>
                        messagesReceived(record.partition).update(_ + 1).as(record)
                      }.flattenChunks
                  }
                  .take(nrMessages.toLong)
                  .runDrain
                  .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer(group, "client3"))
                  .fork
          _                    <- fib.join
          messagesPerPartition <- ZIO.foreach(messagesReceived.values)(_.get)

        } yield assert(messagesPerPartition)(forall(equalTo(nrMessages / nrPartitions)))
      },
      testM("fail when the consuming effect produces a failure") {
        val topic        = "consumeWith3"
        val subscription = Subscription.Topics(Set(topic))
        val nrMessages   = 10
        val messages     = (1 to nrMessages).toList.map(i => (s"key$i", s"msg$i"))

        for {
          _ <- produceMany(topic, messages)
          consumeResult <- consumeWithStrings("group3", "client3", subscription) {
                            case (_, _) =>
                              ZIO.fail(new IllegalArgumentException("consumeWith failure")).orDie
                          }.run
        } yield consumeResult.fold(
          _ => assertCompletes,
          _ => assert("result")(equalTo("Expected consumeWith to fail"))
        )
      } @@ timeout(10.seconds),
      testM("stopConsumption must stop the stream") {
        val kvs = (1 to 100).toList.map(i => (s"key$i", s"msg$i"))
        for {
          topic            <- randomTopic
          group            <- randomGroup
          _                <- produceMany(topic, kvs)
          messagesReceived <- Ref.make[Int](0)
          _ <- Consumer
                .subscribeAnd[Any, String, String](Subscription.topics(topic))
                .plainStream
                .mapM { _ =>
                  for {
                    nr <- messagesReceived.updateAndGet(_ + 1)
                    _  <- Consumer.stopConsumption[Any, String, String].when(nr == 3)
                  } yield ()
                }
                .flattenChunks
                .runDrain
                .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer(group, "client150"))
          nr <- messagesReceived.get
        } yield assert(nr)(isLessThanEqualTo(10)) // NOTE this depends on a max_poll_records setting of 10
      },
      testM("process outstanding commits after a graceful shutdown") {
        val kvs   = (1 to 100).toList.map(i => (s"key$i", s"msg$i"))
        val topic = "test-outstanding-commits"
        for {
          group            <- randomGroup
          _                <- produceMany(topic, kvs)
          messagesReceived <- Ref.make[Int](0)
          offset <- (Consumer
                     .subscribeAnd[Any, String, String](Subscription.topics(topic))
                     .plainStream
                     .mapM { record =>
                       for {
                         nr <- messagesReceived.updateAndGet(_ + 1)
                         _  <- Consumer.stopConsumption[Any, String, String].when(nr == 1)
                       } yield record.offset
                     }
                     .flattenChunks
                     .aggregate(Consumer.offsetBatches)
                     .mapM(_.commit)
                     .runDrain *>
                     Consumer.committed[Any, String, String](Set(new TopicPartition(topic, 0))).map(_.values.head))
                     .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer(group, "client150"))
        } yield assert(offset.map(_.offset))(isSome(isLessThanEqualTo(10L))) // NOTE this depends on a max_poll_records setting of 10
      },
      testM("offset batching collects the latest offset for all partitions") {
        val nrMessages   = 50
        val nrPartitions = 5

        for {
          // Produce messages on several partitions
          topic <- randomTopic
          group <- randomGroup
          _     <- Task(EmbeddedKafka.createCustomTopic(topic, partitions = nrPartitions))
          _ <- ZIO.foreach(1 to nrMessages) { i =>
                produceMany(topic, partition = i % nrPartitions, kvs = List(s"key$i" -> s"msg$i"))
              }

          // Consume messages
          messagesReceived <- ZIO.foreach(0 until nrPartitions)(i => Ref.make[Int](0).map(i -> _)).map(_.toMap)
          subscription     = Subscription.topics(topic)
          offsets <- (Consumer
                      .subscribeAnd[Any, String, String](subscription)
                      .partitionedStream
                      .flatMapPar(nrPartitions)(_._2.map(_.offset).flattenChunks)
                      .take(nrMessages.toLong)
                      .aggregate(Consumer.offsetBatches)
                      .take(1)
                      .mapM(_.commit)
                      .runDrain *>
                      Consumer.committed[Any, String, String](
                        (0 until nrPartitions).map(new TopicPartition(topic, _)).toSet
                      ))
                      .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer(group, "client3"))
        } yield assert(offsets.values.map(_.map(_.offset)))(forall(isSome(equalTo(nrMessages.toLong / nrPartitions))))
      },
      testM("handle rebalancing by completing topic-partition streams") {
        val nrMessages   = 50
        val nrPartitions = 6

        for {
          // Produce messages on several partitions
          topic <- randomTopic
          group <- randomGroup
          _     <- Task(EmbeddedKafka.createCustomTopic(topic, partitions = nrPartitions))
          _ <- ZIO.foreach(1 to nrMessages) { i =>
                produceMany(topic, partition = i % nrPartitions, kvs = List(s"key$i" -> s"msg$i"))
              }

          // Consume messages
          subscription = Subscription.topics(topic)
          consumer1 <- Consumer
                        .subscribeAnd[Any, String, String](subscription)
                        .partitionedStream
                        .flatMapPar(nrPartitions) {
                          case (tp, partition) =>
                            ZStream
                              .fromEffect(partition.flattenChunks.runDrain)
                              .as(tp)
                        }
                        .take(nrPartitions.toLong / 2)
                        .runDrain
                        .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer(group, "client1"))
                        .fork
          _ <- Live.live(ZIO.sleep(5.seconds))
          consumer2 <- Consumer
                        .subscribeAnd[Any, String, String](subscription)
                        .partitionedStream
                        .take(nrPartitions.toLong / 2)
                        .runDrain
                        .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer(group, "client2"))
                        .fork
          _ <- consumer1.join
          _ <- consumer2.join
        } yield assertCompletes
      },
      testM("produce diagnostic events when rebalancing") {
        val nrMessages   = 50
        val nrPartitions = 6

        Diagnostics.SlidingQueue
          .make()
          .use {
            diagnostics =>
              for {
                // Produce messages on several partitions
                topic <- randomTopic
                group <- randomGroup
                _     <- Task(EmbeddedKafka.createCustomTopic(topic, partitions = nrPartitions))
                _ <- ZIO.foreach(1 to nrMessages) { i =>
                      produceMany(topic, partition = i % nrPartitions, kvs = List(s"key$i" -> s"msg$i"))
                    }

                // Consume messages
                subscription = Subscription.topics(topic)
                consumer1 <- Consumer
                              .subscribeAnd[Any, String, String](subscription)
                              .partitionedStream
                              .flatMapPar(nrPartitions) {
                                case (tp, partition) =>
                                  ZStream
                                    .fromEffect(partition.flattenChunks.runDrain)
                                    .as(tp)
                              }
                              .take(nrPartitions.toLong / 2)
                              .runDrain
                              .provideSomeLayer[Kafka with Blocking with Clock](
                                stringConsumer(group, "client1", diagnostics = diagnostics)
                              )
                              .fork
                diagnosticStream <- ZStream
                                     .fromQueue(diagnostics.queue)
                                     .collect { case rebalance: DiagnosticEvent.Rebalance => rebalance }
                                     .runCollect
                                     .fork
                _ <- ZIO.sleep(5.seconds)
                consumer2 <- Consumer
                              .subscribeAnd[Any, String, String](subscription)
                              .partitionedStream
                              .take(nrPartitions.toLong / 2)
                              .runDrain
                              .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer(group, "client2"))
                              .fork
                _ <- consumer1.join
                _ <- consumer1.join
                _ <- consumer2.join
              } yield diagnosticStream.join
          }
          .flatten
          .map { diagnosticEvents =>
            assert(diagnosticEvents.size)(isGreaterThanEqualTo(2))
          }
      },
      testM("support manual seeking") {
        val nrRecords        = 10
        val data             = (1 to nrRecords).toList.map(i => s"key$i" -> s"msg$i")
        val manualOffsetSeek = 3

        for {
          topic <- randomTopic
          _     <- produceMany(topic, 0, data)
          // Consume 5 records to have the offset committed at 5
          _ <- Consumer
                .subscribeAnd[Any, String, String](Subscription.topics(topic))
                .plainStream
                .flattenChunks
                .take(5)
                .transduce(ZSink.collectAll[CommittableRecord[String, String]])
                .mapConcatM { committableRecords =>
                  val records = committableRecords.map(_.record)
                  val offsetBatch =
                    committableRecords.foldLeft(OffsetBatch.empty)(_ merge _.offset)

                  offsetBatch.commit.as(records)
                }
                .runCollect
                .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer("group1", "client1"))
          // Start a new consumer with manual offset before the committed offset
          offsetRetrieval = OffsetRetrieval.Manual(tps => ZIO(tps.map(_ -> manualOffsetSeek.toLong).toMap))
          secondResults <- Consumer
                            .subscribeAnd[Any, String, String](Subscription.topics(topic))
                            .plainStream
                            .take(nrRecords - manualOffsetSeek)
                            .map(_.record)
                            .runCollect
                            .provideSomeLayer[Kafka with Blocking with Clock](
                              stringConsumer("group1", "client2", offsetRetrieval)
                            )
          // Check that we only got the records starting from the manually seek'd offset
        } yield assert(secondResults.map(rec => rec.key() -> rec.value()))(equalTo(data.drop(manualOffsetSeek)))
      },
      testM("commit offsets for all consumed messages") {
        val topic        = "consumeWith2"
        val subscription = Subscription.Topics(Set(topic))
        val nrMessages   = 50
        val messages     = (1 to nrMessages).toList.map(i => (s"key$i", s"msg$i"))

        def consumeIt(messagesReceived: Ref[List[(String, String)]], done: Promise[Nothing, Unit]) =
          consumeWithStrings("group3", "client3", subscription)({ (key, value) =>
            (for {
              messagesSoFar <- messagesReceived.updateAndGet(_ :+ (key -> value))
              _             <- Task.when(messagesSoFar.size == nrMessages)(done.succeed(()))
            } yield ()).orDie
          }).fork

        for {
          done             <- Promise.make[Nothing, Unit]
          messagesReceived <- Ref.make(List.empty[(String, String)])
          _                <- produceMany(topic, messages)
          fib              <- consumeIt(messagesReceived, done)
          _ <- done.await *> Live
                .live(ZIO.sleep(3.seconds)) // TODO the sleep is necessary for the outstanding commits to be flushed. Maybe we can fix that another way
          _ <- fib.interrupt
          _ <- produceOne(topic, "key-new", "msg-new")
          newMessage <- (Consumer.subscribe[Any, String, String](subscription) *> Consumer
                         .plainStream[Any, String, String]
                         .take(1)
                         .flattenChunks
                         .map(r => (r.record.key(), r.record.value()))
                         .run(ZSink.collectAll[(String, String)])
                         .map(_.head)
                         .orDie)
                         .provideSomeLayer[Kafka with Blocking with Clock](stringConsumer("group3", "client3"))
          consumedMessages <- messagesReceived.get
        } yield assert(consumedMessages)(contains(newMessage).negate)
      }
    ).provideSomeLayerShared[TestEnvironment](
      ((Kafka.embedded >>> stringProducer) ++ Kafka.embedded).mapError(TestFailure.fail) ++ Clock.live
    ) @@ timeout(180.seconds)
}
