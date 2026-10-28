# On-device YAMNet audio features

Source: https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite

Original SHA-256: `4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf`

`yamnet_features.tflite` is the same float32 model with subgraph 0 outputs set to tensor indices `[120, 115]`: the existing 521 AudioSet category scores and the existing 1024-dimensional pooled embedding. No weights were changed or retrained. The metadata-associated label list was extracted unchanged into `labels.txt`. The original appended metadata ZIP is not needed by the direct Interpreter API.

Derived model SHA-256: `acb891112c041843f4234f57916b00311a9925e5652932adf4051649020dd091`

Model and upstream code: [tensorflow/models YAMNet](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet), Apache License 2.0, reproduced in LICENSE-YAMNET.txt. Runtime: bundled LiteRT Interpreter 1.4.2. No Google Play download is required.

The application performs audio matching only; it does not perform medical diagnosis or identify a speaker. Cough and similar throat-clearing sounds may overlap. Audio is processed locally and never uploaded.
