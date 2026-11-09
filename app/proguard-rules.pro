# Apache MINA SSHD uses service loading/reflection on Android.
-keep,allowoptimization,allowobfuscation class org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory { *; }
-keep,allowoptimization,allowobfuscation class org.apache.sshd.common.session.helpers.SessionHelper { *; }
-keep,allowoptimization class org.apache.sshd.common.util.security.bouncycastle.BouncyCastleSecurityProviderRegistrar { *; }
-keep,allowoptimization class org.apache.sshd.common.util.security.eddsa.EdDSASecurityProviderRegistrar { *; }
-keep,allowoptimization class org.apache.sshd.common.util.security.SunJCESecurityProviderRegistrar { *; }
-dontwarn org.apache.sshd.**
