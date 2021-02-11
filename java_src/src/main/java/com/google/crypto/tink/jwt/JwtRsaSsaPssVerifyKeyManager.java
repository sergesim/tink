// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////
package com.google.crypto.tink.jwt;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.crypto.tink.KeyTypeManager;
import com.google.crypto.tink.proto.JwtRsaSsaPssAlgorithm;
import com.google.crypto.tink.proto.JwtRsaSsaPssPublicKey;
import com.google.crypto.tink.proto.KeyData.KeyMaterialType;
import com.google.crypto.tink.subtle.EngineFactory;
import com.google.crypto.tink.subtle.Enums;
import com.google.crypto.tink.subtle.RsaSsaPssVerifyJce;
import com.google.crypto.tink.subtle.Validators;
import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;

/**
 * This key manager produces new instances of {@code JwtRsaSsaPss1Verify}. It doesn't support key
 * generation.
 */
class JwtRsaSsaPssVerifyKeyManager extends KeyTypeManager<JwtRsaSsaPssPublicKey> {

  // Note: each algorithm defines not just the modulo size, but also the
  // hash length and salt length to use.
  // See https://www.rfc-editor.org/rfc/rfc7518.html#section-3.5
  static final Enums.HashType hashForPssAlgorithm(JwtRsaSsaPssAlgorithm algorithm)
      throws GeneralSecurityException {
    switch (algorithm) {
      case PS256:
        return Enums.HashType.SHA256;
      case PS384:
        return Enums.HashType.SHA384;
      case PS512:
        return Enums.HashType.SHA512;
      default:
        throw new GeneralSecurityException("unknown algorithm " + algorithm.name());
    }
  }

  static final int saltLengthForPssAlgorithm(JwtRsaSsaPssAlgorithm algorithm)
      throws GeneralSecurityException {
    switch (algorithm) {
      case PS256:
        return 32;
      case PS384:
        return 48;
      case PS512:
        return 64;
      default:
        throw new GeneralSecurityException("unknown algorithm " + algorithm.name());
    }
  }

  private static final RSAPublicKey createPublicKey(JwtRsaSsaPssPublicKey keyProto)
      throws GeneralSecurityException {
    java.security.KeyFactory kf = EngineFactory.KEY_FACTORY.getInstance("RSA");
    BigInteger modulus = new BigInteger(1, keyProto.getN().toByteArray());
    BigInteger exponent = new BigInteger(1, keyProto.getE().toByteArray());
    return (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(modulus, exponent));
  }

  public JwtRsaSsaPssVerifyKeyManager() {
    super(
        JwtRsaSsaPssPublicKey.class,
        new KeyTypeManager.PrimitiveFactory<JwtPublicKeyVerify, JwtRsaSsaPssPublicKey>(
            JwtPublicKeyVerify.class) {
          @Override
          public JwtPublicKeyVerify getPrimitive(JwtRsaSsaPssPublicKey keyProto)
              throws GeneralSecurityException {
            RSAPublicKey publickey = createPublicKey(keyProto);
            Enums.HashType hash = hashForPssAlgorithm(keyProto.getAlgorithm());
            int saltLength = saltLengthForPssAlgorithm(keyProto.getAlgorithm());
            final RsaSsaPssVerifyJce verifier =
                new RsaSsaPssVerifyJce(publickey, hash, hash, saltLength);
            final String algorithmName = keyProto.getAlgorithm().name();
            return new JwtPublicKeyVerify() {
              @Override
              public VerifiedJwt verify(String compact, JwtValidator validator)
                  throws GeneralSecurityException {
                JwtFormat.validateASCII(compact);
                String[] parts = compact.split("\\.", -1);
                if (parts.length != 3) {
                  throw new JwtInvalidException(
                      "only tokens in JWS compact serialization format are supported");
                }
                String unsignedCompact = parts[0] + "." + parts[1];
                byte[] expectedSignature = JwtFormat.decodeSignature(parts[2]);

                verifier.verify(expectedSignature, unsignedCompact.getBytes(US_ASCII));
                JwtFormat.validateHeader(algorithmName, JwtFormat.decodeHeader(parts[0]));
                String payload = JwtFormat.decodePayload(parts[1]);
                RawJwt token = RawJwt.fromJsonPayload(payload);
                return validator.validate(token);
              }
            };
          }
        });
  }

  @Override
  public String getKeyType() {
    return "type.googleapis.com/google.crypto.tink.JwtRsaSsaPssPublicKey";
  }

  @Override
  public int getVersion() {
    return 0;
  }

  @Override
  public KeyMaterialType keyMaterialType() {
    return KeyMaterialType.ASYMMETRIC_PUBLIC;
  }

  @Override
  public JwtRsaSsaPssPublicKey parseKey(ByteString byteString)
      throws InvalidProtocolBufferException {
    return JwtRsaSsaPssPublicKey.parseFrom(byteString, ExtensionRegistryLite.getEmptyRegistry());
  }

  @Override
  public void validateKey(JwtRsaSsaPssPublicKey pubKey) throws GeneralSecurityException {
    Validators.validateVersion(pubKey.getVersion(), getVersion());
    Validators.validateRsaModulusSize(new BigInteger(1, pubKey.getN().toByteArray()).bitLength());
    Validators.validateRsaPublicExponent(new BigInteger(1, pubKey.getE().toByteArray()));
  }
}
