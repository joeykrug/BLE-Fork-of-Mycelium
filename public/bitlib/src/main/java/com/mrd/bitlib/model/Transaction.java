/*
 * Copyright 2013 Megion Research & Development GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mrd.bitlib.model;

import java.io.Serializable;

import com.mrd.bitlib.model.TransactionInput.TransactionInputParsingException;
import com.mrd.bitlib.model.TransactionOutput.TransactionOutputParsingException;
import com.mrd.bitlib.util.ByteReader;
import com.mrd.bitlib.util.ByteWriter;
import com.mrd.bitlib.util.HashUtils;
import com.mrd.bitlib.util.Sha256Hash;
import com.mrd.bitlib.util.ByteReader.InsufficientBytesException;

public class Transaction implements Serializable {
   private static final long serialVersionUID = 1L;

   public static class TransactionParsingException extends Exception {
      private static final long serialVersionUID = 1L;

      public TransactionParsingException(String message) {
         super(message);
      }

      public TransactionParsingException(String message, Exception e) {
         super(message, e);
      }
   }

   public static final int MIN_TRANSACTION_SIZE = 100;
   public int version;
   public TransactionInput[] inputs;
   public TransactionOutput[] outputs;
   public int lockTime;

   private Sha256Hash _hash;
   private Sha256Hash _unmalleableHash;

   public static Transaction fromByteReader(ByteReader reader) throws TransactionParsingException {
      try {
         int version = reader.getIntLE();
         int numInputs = (int) reader.getCompactInt();
         TransactionInput[] inputs = new TransactionInput[numInputs];
         for (int i = 0; i < numInputs; i++) {
            try {
               inputs[i] = TransactionInput.fromByteReader(reader);
            } catch (TransactionInputParsingException e) {
               throw new TransactionParsingException("Unable to parse tranaction input at index " + i + ": "
                     + e.getMessage());
            }
         }
         int numOutputs = (int) reader.getCompactInt();
         TransactionOutput[] outputs = new TransactionOutput[numOutputs];
         for (int i = 0; i < numOutputs; i++) {
            try {
               outputs[i] = TransactionOutput.fromByteReader(reader);
            } catch (TransactionOutputParsingException e) {
               throw new TransactionParsingException("Unable to parse tranaction output at index " + i + ": "
                     + e.getMessage());
            }
         }
         int lockTime = reader.getIntLE();
         Transaction t = new Transaction(version, inputs, outputs, lockTime);
         return t;
      } catch (InsufficientBytesException e) {
         throw new TransactionParsingException(e.getMessage());
      }
   }

   public Transaction copy() {
      try {
         return Transaction.fromByteReader(new ByteReader(toBytes()));
      } catch (TransactionParsingException e) {
         // This should never happen
         throw new RuntimeException(e);
      }
   }

   public byte[] toBytes() {
      ByteWriter writer = new ByteWriter(1024);
      toByteWriter(writer);
      return writer.toBytes();
   }

   public void toByteWriter(ByteWriter writer) {
      writer.putIntLE(version);
      writer.putCompactInt(inputs.length);
      for (TransactionInput input : inputs) {
         input.toByteWriter(writer);
      }
      writer.putCompactInt(outputs.length);
      for (TransactionOutput output : outputs) {
         output.toByteWriter(writer);
      }
      writer.putIntLE(lockTime);
   }

   public Transaction(int version, TransactionInput[] inputs, TransactionOutput[] outputs, int lockTime) {
      this.version = version;
      this.inputs = inputs;
      this.outputs = outputs;
      this.lockTime = lockTime;
   }

   public Sha256Hash getHash() {
      if (_hash == null) {
         ByteWriter writer = new ByteWriter(2000);
         toByteWriter(writer);
         _hash = HashUtils.doubleSha256(writer.toBytes()).reverse();
      }
      return _hash;
   }

   /**
    * Calculate the unmalleable hash of this transaction. If the signature bytes
    * for an input cannot be determined the result is null
    */
   public Sha256Hash getUmnalleableHash() {
      if (_unmalleableHash == null) {
         ByteWriter writer = new ByteWriter(2000);
         for (TransactionInput i : inputs) {
            byte[] bytes = i.getUnmalleableBytes();
            if (bytes == null) {
               return null;
            }
            writer.putBytes(bytes);
         }
         _unmalleableHash = HashUtils.doubleSha256(writer.toBytes()).reverse();
      }
      return _unmalleableHash;
   }

   @Override
   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(getHash()).append(" in: ").append(inputs.length).append(" out: ").append(outputs.length);
      return sb.toString();
   }

   @Override
   public int hashCode() {
      return getHash().hashCode();
   }

   @Override
   public boolean equals(Object other) {
      if (other == this) {
         return true;
      }
      if (!(other instanceof Transaction)) {
         return false;
      }
      return getHash().equals(((Transaction) other).getHash());
   }

   public boolean isCoinbase() {
      for (TransactionInput in : inputs) {
         if (in.script instanceof ScriptInputCoinbase) {
            return true;
         }
      }
      return false;
   }
}
