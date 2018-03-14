## A Kafka app which tests consumer offset correction.

Consumer is seeked to a specific offset where there is no data and the question is whether consumer makes offset correction or not.

There are 3 cases to test:
* Offset is before earliest
* Offset is after latest

## Output and conclusion
```
>>> 20/09/03 16:57:10 INFO kafka.KafkaConsumerOffsetCorrection$: Deleting records
>>> 20/09/03 16:57:10 INFO log.Log: [Log partition=1f71362f-ec54-4a47-8117-36c6b20b5924-0, dir=/private/var/folders/jd/35_sh46s7zq0qc6khfw8hc800000gn/T/spark-5d1ae2f0-5dfc-434d-9a30-80dbbee0ef11] Incremented log start offset to 2 due to client delete records request
>>> 20/09/03 16:57:10 INFO kafka.KafkaConsumerOffsetCorrection$: OK
>>> 20/09/03 16:57:10 INFO internals.SubscriptionState: [Consumer clientId=consumer-70b522c3-e19d-4e42-b979-bc22d16548f5-1, groupId=70b522c3-e19d-4e42-b979-bc22d16548f5] Seeking to EARLIEST offset of partition 1f71362f-ec54-4a47-8117-36c6b20b5924-0
>>> 20/09/03 16:57:10 INFO internals.SubscriptionState: [Consumer clientId=consumer-70b522c3-e19d-4e42-b979-bc22d16548f5-1, groupId=70b522c3-e19d-4e42-b979-bc22d16548f5] Resetting offset for partition 1f71362f-ec54-4a47-8117-36c6b20b5924-0 to offset 2.
>>> 20/09/03 16:57:10 INFO kafka.KafkaConsumerOffsetCorrection$: Earliest after deletion: 2
>>> 20/09/03 16:57:10 INFO internals.SubscriptionState: [Consumer clientId=consumer-70b522c3-e19d-4e42-b979-bc22d16548f5-1, groupId=70b522c3-e19d-4e42-b979-bc22d16548f5] Seeking to LATEST offset of partition 1f71362f-ec54-4a47-8117-36c6b20b5924-0
>>> 20/09/03 16:57:10 INFO internals.SubscriptionState: [Consumer clientId=consumer-70b522c3-e19d-4e42-b979-bc22d16548f5-1, groupId=70b522c3-e19d-4e42-b979-bc22d16548f5] Resetting offset for partition 1f71362f-ec54-4a47-8117-36c6b20b5924-0 to offset 20.
>>> 20/09/03 16:57:10 INFO kafka.KafkaConsumerOffsetCorrection$: Latest after deletion: 20
>>> 20/09/03 16:57:10 INFO kafka.KafkaConsumerOffsetCorrection$: Position before earliest test
>>> 20/09/03 16:57:10 INFO consumer.KafkaConsumer: [Consumer clientId=consumer-70b522c3-e19d-4e42-b979-bc22d16548f5-1, groupId=70b522c3-e19d-4e42-b979-bc22d16548f5] Seeking to offset 0 for partition 1f71362f-ec54-4a47-8117-36c6b20b5924-0
>>> 20/09/03 16:57:10 INFO kafka.KafkaConsumerOffsetCorrection$: Position to seek: 0 Position received: 0
>>> 20/09/03 16:57:10 INFO kafka.KafkaConsumerOffsetCorrection$: Position after latest test
>>> 20/09/03 16:57:10 INFO consumer.KafkaConsumer: [Consumer clientId=consumer-70b522c3-e19d-4e42-b979-bc22d16548f5-1, groupId=70b522c3-e19d-4e42-b979-bc22d16548f5] Seeking to offset 100 for partition 1f71362f-ec54-4a47-8117-36c6b20b5924-0
>>> 20/09/03 16:57:10 INFO kafka.KafkaConsumerOffsetCorrection$: Position to seek: 100 Position received: 100
```
As a final conclusion `position` API doesn't do any offset correction when user provides the `seek` offset.
