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

package com.mrd.bitlib;

import com.mrd.bitlib.model.Transaction;
import com.mrd.bitlib.model.TransactionOutput;
import com.mrd.bitlib.model.UnspentTransactionOutput;

public class TransactionUtils {

   /**
    * The default miner fee which we base our fee calculation on
    */
   public static final long DEFAULT_MINER_FEE = 10000;
   
   /**
    * The minimum output value allowed when relaying transactions
    */
   public static final long MINIMUM_OUTPUT_VALUE = 5430;

   /**
    * The priority threshold at which a transaction is considered to be high
    * priority
    */
   private static final long HIGH_PRIORITY_THRESHOLD = 5760000;

   /**
    * The minimum size all transaction outputs have to have in order for the
    * transaction to qualify for high priority
    */
   private static final long HIGH_PRIORITY_MIN_OUTPUT_SIZE = 1000000; // 0.01
   
   /**
    * The maximum size in bytes for a transaction to be considered high priority 
    */
   private static final long HIGH_PRIORITY_MAX_TX_SIZE = 1000; // 1000 bytes

   /**
    * Determine whether a transaction has sufficient fees according to default miner settings
    * @param tx
    *           the transaction to calculate the priority for
    * @param funding
    *           the unspent outputs that fund the transaction
    * @param blockchainHeight
    *           the current block chain height
    */
   public static boolean hasInSufficientFees(Transaction tx, UnspentTransactionOutput[] funding, int blockchainHeight) {
      // Can this transaction be sent without a fee?
      long txPriority = calcuateTransactionPriority(tx, funding, blockchainHeight);
      int txSize = tx.toBytes().length;
      long minOutoutSize = calculateMinOutputValue(tx);
      if (txPriority > HIGH_PRIORITY_THRESHOLD && minOutoutSize >= HIGH_PRIORITY_MIN_OUTPUT_SIZE
            && txSize < HIGH_PRIORITY_MAX_TX_SIZE) {
         // High priority transaction, can be sent without a fee
         return true;
      }

      // A fee is required, does it pay enough fees?
      long feePaid = calculateFeePaid(tx);
      long feeRequired = calculateFeeRequired(txSize);
      return feePaid < feeRequired;
   }

   private static long calculateFeeRequired(int txSize) {
      long minFee = (1 + (txSize / 1000)) * DEFAULT_MINER_FEE;
      return minFee;
   }

   private static long calculateFeePaid(Transaction tx) {
      long fee = 0;
      for (TransactionOutput out : tx.outputs) {
         fee += out.value;
      }
      return fee;
   }

   private static long calculateMinOutputValue(Transaction tx) {
      long min = Long.MAX_VALUE;
      for (TransactionOutput out : tx.outputs) {
         min = Math.min(min, out.value);
      }
      return min;
   }

   /**
    * Calculate the transaction priority
    * 
    * @param tx
    *           the transaction to calculate the priority for
    * @param funding
    *           the unspent outputs that fund the transaction
    * @param blockchainHeight
    *           the current block chain height
    * @return the priority of this transaction
    */
   public static long calcuateTransactionPriority(Transaction tx, UnspentTransactionOutput[] funding, int blockchainHeight) {
      long sum = 0;
      for (UnspentTransactionOutput output : funding) {
         int confirmations = output.height == -1 ? 0 : blockchainHeight - output.height + 1;
         sum += output.value * confirmations;
      }
      int size = tx.toBytes().length;
      long result = sum / size;
      return result;
   }

}
