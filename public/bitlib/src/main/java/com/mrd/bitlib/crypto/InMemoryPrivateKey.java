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

/**
 * Parts of this code was extracted from the Java cryptography library from
 * www.bouncycastle.org.
 */
package com.mrd.bitlib.crypto;

import java.io.Serializable;
import java.math.BigInteger;

import com.google.bitcoinj.Base58;

import com.mrd.bitlib.crypto.ec.EcTools;
import com.mrd.bitlib.crypto.ec.Parameters;
import com.mrd.bitlib.crypto.ec.Point;
import com.mrd.bitlib.model.NetworkParameters;
import com.mrd.bitlib.util.HashUtils;
import com.mrd.bitlib.util.Sha256Hash;

/**
 * A Bitcoin private key that is kept in memory.
 */
public class InMemoryPrivateKey extends PrivateKey implements KeyExporter, Serializable {

   private static final long serialVersionUID = 1L;

   private final BigInteger _privateKey;
   private final PublicKey _publicKey;

   /**
    * Construct a random private key using a secure random source. Using this
    * constructor yields uncompressed public keys.
    */
   public InMemoryPrivateKey(RandomSource randomSource) {
      this(randomSource, false);
   }

   /**
    * Construct a random private key using a secure random source with optional
    * compressed public keys.
    *
    * @param  randomSource
    *           The random source from which the private key will be
    *           deterministically generated.
    * @param compressed
    *           Specifies whether the corresponding public key should be
    *           compressed
    */
   public InMemoryPrivateKey(RandomSource randomSource, boolean compressed) {
      int nBitLength = Parameters.n.bitLength();
      BigInteger d;
      do {
         // Make a BigInteger from bytes to ensure that Andriod and 'classic'
         // java make the same BigIntegers from the same random source with the
         // same seed. Using BigInteger(nBitLength, random)
         // produces different results on Android compared to 'classic' java.
         byte[] bytes = new byte[nBitLength / 8];
         randomSource.nextBytes(bytes);
         bytes[0] = (byte) (bytes[0] & 0x7F); // ensure positive number
         d = new BigInteger(bytes);
      } while (d.equals(BigInteger.ZERO) || (d.compareTo(Parameters.n) >= 0));

      Point Q = EcTools.multiply(Parameters.G, d);
      _privateKey = d;
      if (compressed) {
         // Convert Q to a compressed point on the curve
         Q = new Point(Q.getCurve(), Q.getX(), Q.getY(), true);
      }
      _publicKey = new PublicKey(Q.getEncoded());
   }

   /**
    * Construct from private key bytes. Using this constructor yields
    * uncompressed public keys.
    *
    * @param bytes
    *           The private key as an array of bytes
    */
   public InMemoryPrivateKey(byte[] bytes) {
      this(bytes, false);
   }

   public InMemoryPrivateKey(Sha256Hash hash, boolean compressed) {
       this(hash.getBytes(),compressed);
   }

   /**
    * Construct from private key bytes. Using this constructor yields
    * uncompressed public keys.
    *
    * @param bytes
    *           The private key as an array of bytes
    * @param compressed
    *           Specifies whether the corresponding public key should be
    *           compressed
    */
   public InMemoryPrivateKey(byte[] bytes, boolean compressed) {
      if (bytes.length != 32) {
         throw new IllegalArgumentException("The length must be 32 bytes");
      }
      // Ensure that we treat it as a positive number
      byte[] keyBytes = new byte[33];
      System.arraycopy(bytes, 0, keyBytes, 1, 32);
      _privateKey = new BigInteger(keyBytes);
      Point Q = EcTools.multiply(Parameters.G, _privateKey);
      if (compressed) {
         // Convert Q to a compressed point on the curve
         Q = new Point(Q.getCurve(), Q.getX(), Q.getY(), true);
      }
      _publicKey = new PublicKey(Q.getEncoded());
   }

   /**
    * Construct from private and public key bytes
    *
    * @param priBytes
    *           The private key as an array of bytes
    */
   public InMemoryPrivateKey(byte[] priBytes, byte[] pubBytes) {
      if (priBytes.length != 32) {
         throw new IllegalArgumentException("The length of the array of bytes must be 32");
      }
      // Ensure that we treat it as a positive number
      byte[] keyBytes = new byte[33];
      System.arraycopy(priBytes, 0, keyBytes, 1, 32);
      _privateKey = new BigInteger(keyBytes);
      _publicKey = new PublicKey(pubBytes);
   }

   /**
    * Construct from a base58 encoded key (SIPA format)
    */
   public InMemoryPrivateKey(String base58Encoded, NetworkParameters network) {
      byte[] decoded = Base58.decodeChecked(base58Encoded);

      // Validate format
      if (decoded == null || decoded.length < 33 || decoded.length > 34) {
         throw new IllegalArgumentException("Invalid base58 encoded key");
      }
      if (network.equals(NetworkParameters.productionNetwork) && decoded[0] != (byte) 0x80) {
         throw new IllegalArgumentException("The base58 encoded key is not for the production network");
      }
      if (network.equals(NetworkParameters.testNetwork) && decoded[0] != (byte) 0xEF) {
         throw new IllegalArgumentException("The base58 encoded key is not for the test network");
      }

      // Determine whether compression should be used for the public key
      boolean compressed;
      if (decoded.length == 34) {
         if (decoded[33] != 0x01) {
            throw new IllegalArgumentException("Invalid base58 encoded key");
         }
         // Get rid of the compression indication byte at the end
         byte[] temp = new byte[33];
         System.arraycopy(decoded, 0, temp, 0, temp.length);
         decoded = temp;
         compressed = true;
      } else {
         compressed = false;
      }
      // Make positive and clear network prefix
      decoded[0] = 0;

      _privateKey = new BigInteger(decoded);
      Point Q = EcTools.multiply(Parameters.G, _privateKey);
      if (compressed) {
         // Convert Q to a compressed point on the curve
         Q = new Point(Q.getCurve(), Q.getX(), Q.getY(), true);
      }
      _publicKey = new PublicKey(Q.getEncoded());
   }

   @Override
   public PublicKey getPublicKey() {
      return _publicKey;
   }

   @Override
   protected Signature generateSignature(Sha256Hash messageHash, RandomSource randomSource) {
      BigInteger n = Parameters.n;
      BigInteger e = calculateE(n, messageHash.getBytes()); //leaving strong typing here
      BigInteger r = null;
      BigInteger s = null;
      // 5.3.2
      do // generate s
      {
         BigInteger k = null;
         int nBitLength = n.bitLength();

         do // generate r
         {
            do {
               // make a BigInteger from bytes to ensure that Andriod and
               // 'classic' java make the same BigIntegers
               byte[] bytes = new byte[nBitLength / 8];
               randomSource.nextBytes(bytes);
               bytes[0] = (byte) (bytes[0] & 0x7F); // ensure positive number
               k = new BigInteger(bytes);
            } while (k.equals(BigInteger.ZERO));

            Point p = EcTools.multiply(Parameters.G, k);

            // 5.3.3
            BigInteger x = p.getX().toBigInteger();

            r = x.mod(n);
         } while (r.equals(BigInteger.ZERO));

         BigInteger d = _privateKey;

         s = k.modInverse(n).multiply(e.add(d.multiply(r))).mod(n);
      } while (s.equals(BigInteger.ZERO));

      // Enforce low S value
      if(s.compareTo(Parameters.MAX_SIG_S) == 1){
         // If the signature is larger than MAX_SIG_S, inverse it
         s = Parameters.n.subtract(s);
      }

      return new Signature(r, s);
   }

   private BigInteger calculateE(BigInteger n, byte[] messageHash) {
      if (n.bitLength() > messageHash.length * 8) {
         return new BigInteger(1, messageHash);
      } else {
         int messageBitLength = messageHash.length * 8;
         BigInteger trunc = new BigInteger(1, messageHash);

         if (messageBitLength - n.bitLength() > 0) {
            trunc = trunc.shiftRight(messageBitLength - n.bitLength());
         }

         return trunc;
      }
   }

   @Override
   public byte[] getPrivateKeyBytes() {
      byte[] result = new byte[32];
      byte[] bytes = _privateKey.toByteArray();
      if (bytes.length <= result.length) {
         System.arraycopy(bytes, 0, result, result.length - bytes.length, bytes.length);
      } else {
         // This happens if the most significant bit is set and we have an
         // extra leading zero to avoid a negative BigInteger
         assert bytes.length == 33 && bytes[0] == 0;
         System.arraycopy(bytes, 1, result, 0, bytes.length - 1);
      }
      return result;
   }

   @Override
   public String getBase58EncodedPrivateKey(NetworkParameters network) {
      if (getPublicKey().isCompressed()) {
         return getBase58EncodedPrivateKeyCompressed(network);
      } else {
         return getBase58EncodedPrivateKeyUncompressed(network);
      }
   }

   private String getBase58EncodedPrivateKeyUncompressed(NetworkParameters network) {
      byte[] toEncode = new byte[1 + 32 + 4];
      // Set network
      toEncode[0] = network.isProdnet() ? (byte) 0x80 : (byte) 0xEF;
      // Set key bytes
      byte[] keyBytes = getPrivateKeyBytes();
      System.arraycopy(keyBytes, 0, toEncode, 1, keyBytes.length);
      // Set checksum
      byte[] checkSum = HashUtils.doubleSha256(toEncode, 0, 1 + 32).firstFourBytes();
      System.arraycopy(checkSum, 0, toEncode, 1 + 32, 4);
      // Encode
      return Base58.encode(toEncode);
   }

   private String getBase58EncodedPrivateKeyCompressed(NetworkParameters network) {
      byte[] toEncode = new byte[1 + 32 + 1 + 4];
      // Set network
      toEncode[0] = network.isProdnet() ? (byte) 0x80 : (byte) 0xEF;
      // Set key bytes
      byte[] keyBytes = getPrivateKeyBytes();
      System.arraycopy(keyBytes, 0, toEncode, 1, keyBytes.length);
      // Set compressed indicator
      toEncode[33] = 0x01;
      // Set checksum
      byte[] checkSum = HashUtils.doubleSha256(toEncode, 0, 1 + 32 + 1).firstFourBytes();
      System.arraycopy(checkSum, 0, toEncode, 1 + 32 + 1, 4);
      // Encode
      return Base58.encode(toEncode);
   }

}
