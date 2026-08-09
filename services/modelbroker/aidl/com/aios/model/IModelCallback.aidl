package com.aios.model;

import com.aios.model.GenerationChunk;
import com.aios.model.InferenceResult;

oneway interface IModelCallback {
    void onChunk(in GenerationChunk chunk);
    void onCompleted(in InferenceResult result);
    void onError(int code, String message);
}
