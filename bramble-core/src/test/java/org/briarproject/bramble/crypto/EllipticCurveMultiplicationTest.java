package org.briarproject.bramble.crypto;

import org.bouncycastle.asn1.teletrust.TeleTrusTNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.agreement.ECDHCBasicAgreement;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.MontgomeryLadderMultiplier;
import org.briarproject.bramble.test.BrambleTestCase;
import org.junit.Test;

import java.math.BigInteger;
import java.security.SecureRandom;

import static org.junit.Assert.assertEquals;

public class EllipticCurveMultiplicationTest extends BrambleTestCase {

	@Test
	public void testMultiplierProducesSameResultsAsDefault() throws Exception {
		// Instantiate the default implementation of the curve
		X9ECParameters defaultX9Parameters =
				TeleTrusTNamedCurves.getByName("brainpoolp256r1");
		ECCurve defaultCurve = defaultX9Parameters.getCurve();
		ECPoint defaultG = defaultX9Parameters.getG();
		BigInteger defaultN = defaultX9Parameters.getN();
		BigInteger defaultH = defaultX9Parameters.getH();
		ECDomainParameters defaultParameters = new ECDomainParameters(
				defaultCurve, defaultG, defaultN, defaultH);
		// Instantiate an implementation using the Montgomery ladder multiplier
		ECDomainParameters montgomeryParameters =
				constantTime(defaultParameters);
		// Generate two key pairs with each set of parameters, using the same
		// deterministic PRNG for both sets of parameters
		byte[] seed = new byte[32];
		new SecureRandom().nextBytes(seed);
		// Montgomery ladder multiplier
		SecureRandom random = new PseudoSecureRandom(seed);
		ECKeyGenerationParameters montgomeryGeneratorParams =
				new ECKeyGenerationParameters(montgomeryParameters, random);
		ECKeyPairGenerator montgomeryGenerator = new ECKeyPairGenerator();
		montgomeryGenerator.init(montgomeryGeneratorParams);
		AsymmetricCipherKeyPair montgomeryKeyPair1 =
				montgomeryGenerator.generateKeyPair();
		ECPrivateKeyParameters montgomeryPrivate1 =
				(ECPrivateKeyParameters) montgomeryKeyPair1.getPrivate();
		ECPublicKeyParameters montgomeryPublic1 =
				(ECPublicKeyParameters) montgomeryKeyPair1.getPublic();
		AsymmetricCipherKeyPair montgomeryKeyPair2 =
				montgomeryGenerator.generateKeyPair();
		ECPrivateKeyParameters montgomeryPrivate2 =
				(ECPrivateKeyParameters) montgomeryKeyPair2.getPrivate();
		ECPublicKeyParameters montgomeryPublic2 =
				(ECPublicKeyParameters) montgomeryKeyPair2.getPublic();
		// Default multiplier
		random = new PseudoSecureRandom(seed);
		ECKeyGenerationParameters defaultGeneratorParams =
				new ECKeyGenerationParameters(defaultParameters, random);
		ECKeyPairGenerator defaultGenerator = new ECKeyPairGenerator();
		defaultGenerator.init(defaultGeneratorParams);
		AsymmetricCipherKeyPair defaultKeyPair1 =
				defaultGenerator.generateKeyPair();
		ECPrivateKeyParameters defaultPrivate1 =
				(ECPrivateKeyParameters) defaultKeyPair1.getPrivate();
		ECPublicKeyParameters defaultPublic1 =
				(ECPublicKeyParameters) defaultKeyPair1.getPublic();
		AsymmetricCipherKeyPair defaultKeyPair2 =
				defaultGenerator.generateKeyPair();
		ECPrivateKeyParameters defaultPrivate2 =
				(ECPrivateKeyParameters) defaultKeyPair2.getPrivate();
		ECPublicKeyParameters defaultPublic2 =
				(ECPublicKeyParameters) defaultKeyPair2.getPublic();
		// The key pairs generated with both sets of parameters should be equal
		assertEquals(montgomeryPrivate1.getD(), defaultPrivate1.getD());
		assertEquals(montgomeryPublic1.getQ(), defaultPublic1.getQ());
		assertEquals(montgomeryPrivate2.getD(), defaultPrivate2.getD());
		assertEquals(montgomeryPublic2.getQ(), defaultPublic2.getQ());
		// OK, all of the above was just sanity checks - now for the test!
		ECDHCBasicAgreement agreement = new ECDHCBasicAgreement();
		agreement.init(montgomeryPrivate1);
		BigInteger sharedSecretMontgomeryMontgomery =
				agreement.calculateAgreement(montgomeryPublic2);
		agreement.init(montgomeryPrivate1);
		BigInteger sharedSecretMontgomeryDefault =
				agreement.calculateAgreement(defaultPublic2);
		agreement.init(defaultPrivate1);
		BigInteger sharedSecretDefaultMontgomery =
				agreement.calculateAgreement(montgomeryPublic2);
		agreement.init(defaultPrivate1);
		BigInteger sharedSecretDefaultDefault =
				agreement.calculateAgreement(defaultPublic2);
		// Shared secrets calculated with different multipliers should be equal
		assertEquals(sharedSecretMontgomeryMontgomery,
				sharedSecretMontgomeryDefault);
		assertEquals(sharedSecretMontgomeryMontgomery,
				sharedSecretDefaultMontgomery);
		assertEquals(sharedSecretMontgomeryMontgomery,
				sharedSecretDefaultDefault);
	}

	private static ECDomainParameters constantTime(ECDomainParameters in) {
		ECCurve curve = in.getCurve().configure().setMultiplier(
				new MontgomeryLadderMultiplier()).create();
		BigInteger x = in.getG().getAffineXCoord().toBigInteger();
		BigInteger y = in.getG().getAffineYCoord().toBigInteger();
		ECPoint g = curve.createPoint(x, y);
		return new ECDomainParameters(curve, g, in.getN(), in.getH());
	}
}
