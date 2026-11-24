const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";
const ADMIN_UID = "admin_uid_001";
const DEVICE_HASH = "a".repeat(64);
const OTHER_DEVICE_HASH = "b".repeat(64);

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(offsetMs = 2000) {
  return new Date(Date.now() - offsetMs);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or create device_trials, users, or app_config", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("device_trials").doc(DEVICE_HASH).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("app_config").doc("global_settings").get());
  await assertFails(
    unauthDb.collection("device_trials").doc(DEVICE_HASH).set({
      deviceHash: DEVICE_HASH,
      ownerUid: ALICE_UID,
      lastUserId: ALICE_UID,
      accountSwitchCount: 0,
      trialStartedAt: pastDate(),
      lastVerifiedAt: pastDate(),
      isPremiumDevice: false,
      updatedAt: pastDate(),
    })
  );
});

test("Authenticated user: can create initial device_trials record and cannot list all devices", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastDate();
  await assertSucceeds(
    aliceDb.collection("device_trials").doc(DEVICE_HASH).set({
      deviceHash: DEVICE_HASH,
      ownerUid: ALICE_UID,
      lastUserId: ALICE_UID,
      accountSwitchCount: 0,
      trialStartedAt: ts,
      lastVerifiedAt: ts,
      isPremiumDevice: false,
      updatedAt: ts,
    })
  );

  // Listing all device_trials must fail for non-admin (anti-scraping)
  await assertFails(aliceDb.collection("device_trials").get());
});

test("Device anti-abuse: second account on same device cannot reset trialStartedAt or delete record", async () => {
  const day0 = pastDate(8 * 24 * 60 * 60 * 1000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("device_trials").doc(DEVICE_HASH).set({
      deviceHash: DEVICE_HASH,
      ownerUid: ALICE_UID,
      lastUserId: ALICE_UID,
      accountSwitchCount: 0,
      trialStartedAt: day0,
      lastVerifiedAt: day0,
      isPremiumDevice: false,
      updatedAt: day0,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(bobDb.collection("device_trials").doc(DEVICE_HASH).get());

  const now = pastDate();
  await assertSucceeds(
    bobDb.collection("device_trials").doc(DEVICE_HASH).update({
      lastUserId: BOB_UID,
      accountSwitchCount: 1,
      lastVerifiedAt: now,
      updatedAt: now,
    })
  );

  await assertFails(
    bobDb.collection("device_trials").doc(DEVICE_HASH).update({
      trialStartedAt: now,
      lastUserId: BOB_UID,
      lastVerifiedAt: now,
      updatedAt: now,
    })
  );

  await assertFails(
    bobDb.collection("device_trials").doc(DEVICE_HASH).update({
      ownerUid: BOB_UID,
      lastUserId: BOB_UID,
      lastVerifiedAt: now,
      updatedAt: now,
    })
  );

  await assertFails(bobDb.collection("device_trials").doc(DEVICE_HASH).delete());
});

test("Shadow update & ID poisoning tests fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastDate();

  await assertFails(
    aliceDb.collection("device_trials").doc("short_id").set({
      deviceHash: "short_id",
      ownerUid: ALICE_UID,
      lastUserId: ALICE_UID,
      accountSwitchCount: 0,
      trialStartedAt: ts,
      lastVerifiedAt: ts,
      isPremiumDevice: false,
      updatedAt: ts,
    })
  );

  await assertFails(
    aliceDb.collection("device_trials").doc(OTHER_DEVICE_HASH).set({
      deviceHash: OTHER_DEVICE_HASH,
      ownerUid: ALICE_UID,
      lastUserId: ALICE_UID,
      accountSwitchCount: 0,
      trialStartedAt: ts,
      lastVerifiedAt: ts,
      isPremiumDevice: false,
      updatedAt: ts,
      extraBypassField: true,
    })
  );

  await assertFails(
    aliceDb.collection("device_trials").doc(OTHER_DEVICE_HASH).set({
      deviceHash: OTHER_DEVICE_HASH,
      ownerUid: ALICE_UID,
      lastUserId: ALICE_UID,
      accountSwitchCount: 0,
      trialStartedAt: ts,
      lastVerifiedAt: ts,
      isPremiumDevice: true,
      updatedAt: ts,
    })
  );
});

test("User entitlement: owner isolation, no self-grant bonus days or list all users", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      deviceHash: DEVICE_HASH,
      trialStartedAt: ts,
      isPremiumUser: false,
      subscriptionProductId: "",
      purchaseToken: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
  await assertFails(aliceDb.collection("users").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      isPremiumUser: true,
      updatedAt: pastDate(),
    })
  );

  // Alice CANNOT grant herself bonus trial days
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      bonusTrialDays: 30,
      updatedAt: pastDate(),
    })
  );

  // Alice CANNOT grant herself isPremiumUser directly (must go through verified Admin / Payment Approval)
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isPremiumUser: true,
      subscriptionProductId: "monthly_premium_299",
      purchaseToken: "tok_abc_123",
      updatedAt: pastDate(),
    })
  );

  // Alice CAN update her own displayName / deviceHash and real-time preferences without altering entitlements
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Updated",
      autoCallRecording: true,
      themeMode: "dark",
      themePaletteId: "cyber_neon_3d",
      useDynamicColors: false,
      dialpadTonesEnabled: false,
      blockedNumbers: ["+919876500000"],
      quickDeclineResponses: ["In a meeting, will call back."],
      subscriptionStatus: "pending_approval",
      updatedAt: pastDate(),
    })
  );

  // Alice CAN also switch themeMode to 'system' and toggle useDynamicColors
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      themeMode: "system",
      useDynamicColors: true,
      updatedAt: pastDate(),
    })
  );

  // Alice CANNOT set subscriptionStatus to 'active' herself
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      subscriptionStatus: "active",
      updatedAt: pastDate(),
    })
  );

  // Alice CANNOT create a new user pre-flagged as premium
  await assertFails(
    aliceDb.collection("users").doc("forged_uid").set({
      userId: "forged_uid",
      displayName: "Hacker",
      email: "hacker@example.com",
      deviceHash: DEVICE_HASH,
      trialStartedAt: ts,
      isPremiumUser: true,
      subscriptionProductId: "monthly_premium_299",
      purchaseToken: "forged",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      trialStartedAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).delete());
});

test("Payment requests: user creates valid ₹299 elx@ptaxis pending request; cannot self-approve or spoof UPI/amount", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate();

  const validRequest = {
    userId: ALICE_UID,
    userName: "Alice",
    userEmail: "alice@example.com",
    deviceHash: DEVICE_HASH,
    shortDeviceTag: "DEV-AAAA-AAAA",
    upiId: "elx@ptaxis",
    amountInr: 299,
    txnRef: "PD1700000000",
    upiTxnId: "UPI123456789",
    approvalRefNo: "456789",
    upiResponseStatus: "SUCCESS",
    status: "pending",
    reviewedByUid: "",
    reviewNote: "",
    createdAt: ts,
    updatedAt: ts,
  };

  await assertSucceeds(
    aliceDb.collection("payment_requests").doc("req_alice_1").set(validRequest)
  );

  await assertSucceeds(
    aliceDb.collection("payment_requests").where("userId", "==", ALICE_UID).get()
  );
  await assertFails(aliceDb.collection("payment_requests").get());
  await assertFails(bobDb.collection("payment_requests").doc("req_alice_1").get());

  await assertFails(
    aliceDb.collection("payment_requests").doc("req_alice_1").update({
      status: "approved",
      reviewedByUid: ALICE_UID,
      reviewNote: "Self approved",
      updatedAt: pastDate(),
    })
  );

  await assertFails(
    aliceDb.collection("payment_requests").doc("req_bad_upi").set({
      ...validRequest,
      upiId: "attacker@upi",
    })
  );
  await assertFails(
    aliceDb.collection("payment_requests").doc("req_bad_status").set({
      ...validRequest,
      status: "approved",
    })
  );
});

test("Admin full management: spoofed email fails; verified admin manages users, approves & disapproves payments, and manages app_config", async () => {
  const ts = pastDate(5000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("device_trials").doc(DEVICE_HASH).set({
      deviceHash: DEVICE_HASH,
      ownerUid: ALICE_UID,
      lastUserId: ALICE_UID,
      accountSwitchCount: 0,
      trialStartedAt: pastDate(9 * 24 * 60 * 60 * 1000),
      lastVerifiedAt: ts,
      isPremiumDevice: false,
      updatedAt: ts,
    });
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      deviceHash: DEVICE_HASH,
      trialStartedAt: pastDate(9 * 24 * 60 * 60 * 1000),
      isPremiumUser: false,
      subscriptionProductId: "",
      purchaseToken: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("payment_requests").doc("req_pending_1").set({
      userId: ALICE_UID,
      userName: "Alice",
      userEmail: "alice@example.com",
      deviceHash: DEVICE_HASH,
      shortDeviceTag: "DEV-AAAA-AAAA",
      upiId: "elx@ptaxis",
      amountInr: 299,
      txnRef: "PD1700000001",
      upiTxnId: "412345678901",
      approvalRefNo: "APP999",
      upiResponseStatus: "SUCCESS",
      status: "pending",
      reviewedByUid: "",
      reviewNote: "",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  // Spoof attack: matching admin email but email_verified = false MUST FAIL
  const spoofedAdminDb = testEnv.authenticatedContext("spoof_uid", {
    email: "Jitendrakumar888126@gmail.com",
    email_verified: false,
  }).firestore();
  await assertFails(spoofedAdminDb.collection("payment_requests").get());
  await assertFails(spoofedAdminDb.collection("users").get());
  await assertFails(
    spoofedAdminDb.collection("app_config").doc("global_settings").set({
      trialDays: 14,
      subscriptionPriceInr: 199,
      upiVpa: "elx@ptaxis",
      whatsappNumber: "9012634797",
      enforceSingleDeviceTrial: true,
      allowCallRecording: true,
      allowManualUtr: true,
      maintenanceMode: false,
      maintenanceMessage: "",
      announcementText: "",
      updatedByUid: "spoof_uid",
      updatedAt: pastDate(),
    })
  );

  // Regular user cannot update app_config
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("app_config").doc("global_settings").set({
      trialDays: 365,
      subscriptionPriceInr: 1,
      upiVpa: "elx@ptaxis",
      whatsappNumber: "9012634797",
      enforceSingleDeviceTrial: false,
      allowCallRecording: true,
      allowManualUtr: true,
      maintenanceMode: false,
      maintenanceMessage: "",
      announcementText: "",
      updatedByUid: ALICE_UID,
      updatedAt: pastDate(),
    })
  );

  // Verified admin succeeds
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "Jitendrakumar888126@gmail.com",
    email_verified: true,
  }).firestore();

  await assertSucceeds(adminDb.collection("payment_requests").get());
  await assertSucceeds(adminDb.collection("users").get());
  await assertSucceeds(adminDb.collection("device_trials").get());

  // Admin manages global app_config settings
  const now = pastDate();
  await assertSucceeds(
    adminDb.collection("app_config").doc("global_settings").set({
      trialDays: 7,
      subscriptionPriceInr: 299,
      upiVpa: "elx@ptaxis",
      whatsappNumber: "9012634797",
      enforceSingleDeviceTrial: true,
      allowCallRecording: true,
      allowManualUtr: true,
      maintenanceMode: false,
      maintenanceMessage: "",
      announcementText: "Welcome to PulseDial!",
      dialpadTonesDefault: false,
      defaultAutoRecording: true,
      forceThemeMode: "dark",
      updatedByUid: ADMIN_UID,
      updatedAt: now,
    })
  );

  // Invalid forceThemeMode must fail validation
  await assertFails(
    adminDb.collection("app_config").doc("global_settings").set({
      trialDays: 7,
      subscriptionPriceInr: 299,
      upiVpa: "elx@ptaxis",
      whatsappNumber: "9012634797",
      enforceSingleDeviceTrial: true,
      allowCallRecording: true,
      allowManualUtr: true,
      maintenanceMode: false,
      maintenanceMessage: "",
      announcementText: "Bad theme",
      dialpadTonesDefault: true,
      defaultAutoRecording: false,
      forceThemeMode: "neon_invalid",
      updatedByUid: ADMIN_UID,
      updatedAt: now,
    })
  );

  // Any signed-in user can read global_settings
  await assertSucceeds(aliceDb.collection("app_config").doc("global_settings").get());

  // Alice CANNOT grant herself a subscriptionExpiresAt date
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      subscriptionExpiresAt: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000),
      updatedAt: pastDate(),
    })
  );

  // Admin approves payment request & unlocks user + device for 30 days
  const thirtyDaysOut = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  await assertSucceeds(
    adminDb.collection("payment_requests").doc("req_pending_1").update({
      status: "approved",
      reviewedByUid: ADMIN_UID,
      reviewNote: "Verified on elx@ptaxis",
      updatedAt: now,
    })
  );
  await assertSucceeds(
    adminDb.collection("users").doc(ALICE_UID).update({
      isPremiumUser: true,
      subscriptionProductId: "upi_monthly_299",
      purchaseToken: "412345678901",
      subscriptionExpiresAt: thirtyDaysOut,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    adminDb.collection("device_trials").doc(DEVICE_HASH).update({
      isPremiumDevice: true,
      subscriptionExpiresAt: thirtyDaysOut,
      updatedAt: now,
    })
  );

  // Admin can also DISAPPROVE an approved payment request and revoke/moderate user!
  await assertSucceeds(
    adminDb.collection("payment_requests").doc("req_pending_1").update({
      status: "rejected",
      reviewedByUid: ADMIN_UID,
      reviewNote: "Chargeback / Disapproved by admin",
      updatedAt: pastDate(),
    })
  );
  await assertSucceeds(
    adminDb.collection("users").doc(ALICE_UID).update({
      isPremiumUser: false,
      subscriptionExpiresAt: null,
      isBlocked: true,
      bonusTrialDays: 7,
      adminNote: "Suspended pending verification",
      hideSubscriptionUI: true,
      updatedAt: pastDate(),
    })
  );
});

test("Server-driven navigation_tabs, settings_items, live_broadcast & hideSubscriptionUI rules", async () => {
  const ts = pastDate();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "Jitendrakumar888126@gmail.com",
    email_verified: true,
  }).firestore();

  // Create Alice user doc
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      phoneNumber: "9876543210",
      deviceHash: DEVICE_HASH,
      trialStartedAt: ts,
      isPremiumUser: false,
      subscriptionProductId: "",
      purchaseToken: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Alice CANNOT hide subscription UI on herself
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      hideSubscriptionUI: true,
      updatedAt: pastDate(),
    })
  );

  // Alice CANNOT write navigation_tabs, settings_items, or live_broadcast
  await assertFails(
    aliceDb.collection("app_config").doc("navigation_tabs").set({
      tabs: [{ id: "history", title: "Hacked", enabled: true, order: 0 }],
      updatedByUid: ALICE_UID,
      updatedAt: ts,
    })
  );
  await assertFails(
    aliceDb.collection("app_config").doc("live_broadcast").set({
      showPopup: true,
      popupId: "hack",
      title: "Hack",
      message: "Bad",
      imageUrl: "",
      buttonText: "Go",
      actionUrl: "",
      isCancelable: true,
      showBannerAd: false,
      bannerText: "",
      bannerImageUrl: "",
      bannerActionUrl: "",
      updatedByUid: ALICE_UID,
      updatedAt: ts,
    })
  );

  // Verified Admin succeeds writing navigation_tabs, settings_items, live_broadcast, and global hideSubscriptionUI
  await assertSucceeds(
    adminDb.collection("app_config").doc("navigation_tabs").set({
      tabs: [{ id: "history", title: "Calls", enabled: true, order: 0, type: "builtin_history" }],
      updatedByUid: ADMIN_UID,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    adminDb.collection("app_config").doc("settings_items").set({
      items: [{ key: "auto_recording", title: "Auto Rec", enabled: true, order: 0, type: "toggle" }],
      updatedByUid: ADMIN_UID,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    adminDb.collection("app_config").doc("live_broadcast").set({
      showPopup: true,
      popupId: "pop_1",
      title: "Festive Offer",
      message: "Get PulseDial Pro today!",
      imageUrl: "https://example.com/banner.png",
      buttonText: "Upgrade",
      actionUrl: "https://example.com/pro",
      isCancelable: true,
      showBannerAd: true,
      bannerText: "Pro Offer Live",
      bannerImageUrl: "",
      bannerActionUrl: "https://example.com/pro",
      updatedByUid: ADMIN_UID,
      updatedAt: ts,
    })
  );

  // Signed-in user can read all three server-driven docs
  await assertSucceeds(aliceDb.collection("app_config").doc("navigation_tabs").get());
  await assertSucceeds(aliceDb.collection("app_config").doc("settings_items").get());
  await assertSucceeds(aliceDb.collection("app_config").doc("live_broadcast").get());
});

test("Verified Admin can add and delete regular users, while non-admins cannot and Super Admins cannot be deleted", async () => {
  const ts = pastDate();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "jitendrakumar888126@gmail.com",
    email_verified: true,
  }).firestore();

  // Super Admin doc seeded via admin/system context
  const superAdminUid = "super_admin_pushpendra";
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc(superAdminUid).set({
      userId: superAdminUid,
      displayName: "Pushpendra",
      email: "0143pushpendra@gmail.com",
      deviceHash: "",
      trialStartedAt: ts,
      isPremiumUser: true,
      subscriptionProductId: "admin_pro",
      purchaseToken: "manual",
      isAdmin: true,
      adminGrantedBy: "0143pushpendra@gmail.com",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  // Non-admin CANNOT create a user doc for someone else
  await assertFails(
    aliceDb.collection("users").doc("usr_created_by_alice").set({
      userId: "usr_created_by_alice",
      displayName: "Unauthorized User",
      email: "unauth@example.com",
      deviceHash: "",
      trialStartedAt: ts,
      isPremiumUser: true,
      subscriptionProductId: "admin_added_pro",
      purchaseToken: "admin_manual",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Verified Admin CAN add a new user (with custom Pro days)
  const thirtyDaysOut = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  await assertSucceeds(
    adminDb.collection("users").doc("usr_added_by_admin_1").set({
      userId: "usr_added_by_admin_1",
      displayName: "Manual Pro Member",
      email: "manualpro@example.com",
      phoneNumber: "+919876543210",
      deviceHash: "",
      trialStartedAt: ts,
      isPremiumUser: true,
      subscriptionStatus: "active",
      subscriptionProductId: "admin_added_pro",
      purchaseToken: "admin_manual",
      paymentRefId: "",
      subscriptionExpiresAt: thirtyDaysOut,
      isBlocked: false,
      isAdmin: false,
      adminGrantedBy: "",
      bonusTrialDays: 0,
      adminNote: "Added from Admin Panel",
      hideSubscriptionUI: false,
      autoCallRecording: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Non-admin CANNOT delete any user doc (not even own or added user)
  await assertFails(aliceDb.collection("users").doc("usr_added_by_admin_1").delete());

  // Admin CANNOT delete a Super Admin account
  await assertFails(adminDb.collection("users").doc(superAdminUid).delete());

  // Verified Admin CAN delete a regular user doc
  await assertSucceeds(adminDb.collection("users").doc("usr_added_by_admin_1").delete());
});

test("Verified Admin can permanently clean up user payment_requests and device_trials, and push/delete Update & Ad popups", async () => {
  const ts = pastDate();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "jitendrakumar888126@gmail.com",
    email_verified: true,
  }).firestore();

  // Seed Alice's device_trial and payment_request
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("device_trials").doc(DEVICE_HASH).set({
      deviceHash: DEVICE_HASH,
      ownerUid: ALICE_UID,
      lastUserId: ALICE_UID,
      accountSwitchCount: 0,
      trialStartedAt: ts,
      lastVerifiedAt: ts,
      isPremiumDevice: false,
      updatedAt: ts,
    });
    await db.collection("payment_requests").doc("pay_alice_1").set({
      userId: ALICE_UID,
      userEmail: "alice@example.com",
      userName: "Alice",
      deviceHash: DEVICE_HASH,
      upiId: "test@upi",
      amountInr: 299,
      txnRef: "TXN001",
      upiTxnId: "UTR123456789012",
      approvalRefNo: "123456789012",
      upiResponseStatus: "SUBMITTED",
      status: "pending",
      reviewedByUid: "",
      reviewNote: "",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  // Non-admin CANNOT delete payment_requests or device_trials
  await assertFails(aliceDb.collection("payment_requests").doc("pay_alice_1").delete());
  await assertFails(aliceDb.collection("device_trials").doc(DEVICE_HASH).delete());

  // Verified Admin CAN permanently delete user's payment_requests and device_trials
  await assertSucceeds(adminDb.collection("payment_requests").doc("pay_alice_1").delete());
  await assertSucceeds(adminDb.collection("device_trials").doc(DEVICE_HASH).delete());

  // Verified Admin CAN push App Update Popup with popupType, version, forceUpdate, showOnce
  await assertSucceeds(
    adminDb.collection("app_config").doc("live_broadcast").set({
      showPopup: true,
      popupId: "popup_1700000000001",
      popupType: "update",
      version: "v2.0",
      forceUpdate: true,
      showOnce: true,
      title: "New Update Available",
      message: "Faster call detection and bug fixes.",
      imageUrl: "",
      buttonText: "Update Now",
      actionUrl: "https://example.com/update",
      isCancelable: false,
      showBannerAd: false,
      bannerText: "",
      bannerImageUrl: "",
      bannerActionUrl: "",
      updatedByUid: ADMIN_UID,
      updatedAt: ts,
    })
  );

  // Verified Admin CAN push Custom Ad Popup with popupType = "ad"
  await assertSucceeds(
    adminDb.collection("app_config").doc("live_broadcast").set({
      showPopup: true,
      popupId: "popup_1700000000002",
      popupType: "ad",
      version: "",
      forceUpdate: false,
      showOnce: true,
      title: "Special Offer",
      message: "Check out our new launch!",
      imageUrl: "https://example.com/ad.png",
      buttonText: "Check Now",
      actionUrl: "https://example.com/offer",
      isCancelable: true,
      showBannerAd: false,
      bannerText: "",
      bannerImageUrl: "",
      bannerActionUrl: "",
      updatedByUid: ADMIN_UID,
      updatedAt: ts,
    })
  );

  // Invalid popupType must fail validation
  await assertFails(
    adminDb.collection("app_config").doc("live_broadcast").set({
      showPopup: true,
      popupId: "popup_bad",
      popupType: "malware",
      version: "",
      forceUpdate: false,
      showOnce: true,
      title: "Bad",
      message: "Bad",
      imageUrl: "",
      buttonText: "Go",
      actionUrl: "",
      isCancelable: true,
      showBannerAd: false,
      bannerText: "",
      bannerImageUrl: "",
      bannerActionUrl: "",
      updatedByUid: ADMIN_UID,
      updatedAt: ts,
    })
  );

  // Non-admin CANNOT delete live_broadcast, Verified Admin CAN delete it
  await assertFails(aliceDb.collection("app_config").doc("live_broadcast").delete());
  await assertSucceeds(adminDb.collection("app_config").doc("live_broadcast").delete());
});


test("Verified Admin can permanently clean up user payment_requests and device_trials, and push/delete Update & Ad popups", async () => {
  const ts = pastDate();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "jitendrakumar888126@gmail.com",
    email_verified: true,
  }).firestore();

  // Seed Alice's device_trial and payment_request
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("device_trials").doc(DEVICE_HASH).set({
      deviceHash: DEVICE_HASH,
      ownerUid: ALICE_UID,
      lastUserId: ALICE_UID,
      accountSwitchCount: 0,
      trialStartedAt: ts,
      lastVerifiedAt: ts,
      isPremiumDevice: false,
      updatedAt: ts,
    });
    await db.collection("payment_requests").doc("pay_alice_1").set({
      userId: ALICE_UID,
      userEmail: "alice@example.com",
      userName: "Alice",
      deviceHash: DEVICE_HASH,
      upiId: "test@upi",
      amountInr: 299,
      txnRef: "TXN001",
      upiTxnId: "UTR123456789012",
      approvalRefNo: "123456789012",
      upiResponseStatus: "SUBMITTED",
      status: "pending",
      reviewedByUid: "",
      reviewNote: "",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  // Non-admin CANNOT delete payment_requests or device_trials
  await assertFails(aliceDb.collection("payment_requests").doc("pay_alice_1").delete());
  await assertFails(aliceDb.collection("device_trials").doc(DEVICE_HASH).delete());

  // Verified Admin CAN permanently delete user's payment_requests and device_trials
  await assertSucceeds(adminDb.collection("payment_requests").doc("pay_alice_1").delete());
  await assertSucceeds(adminDb.collection("device_trials").doc(DEVICE_HASH).delete());

  // Verified Admin CAN push App Update Popup with popupType, version, forceUpdate, showOnce
  await assertSucceeds(
    adminDb.collection("app_config").doc("live_broadcast").set({
      showPopup: true,
      popupId: "popup_1700000000001",
      popupType: "update",
      version: "v2.0",
      forceUpdate: true,
      showOnce: true,
      title: "New Update Available",
      message: "Faster call detection and bug fixes.",
      imageUrl: "",
      buttonText: "Update Now",
      actionUrl: "https://example.com/update",
      isCancelable: false,
      showBannerAd: false,
      bannerText: "",
      bannerImageUrl: "",
      bannerActionUrl: "",
      updatedByUid: ADMIN_UID,
      updatedAt: ts,
    })
  );

  // Verified Admin CAN push Custom Ad Popup with popupType = "ad"
  await assertSucceeds(
    adminDb.collection("app_config").doc("live_broadcast").set({
      showPopup: true,
      popupId: "popup_1700000000002",
      popupType: "ad",
      version: "",
      forceUpdate: false,
      showOnce: true,
      title: "Special Offer",
      message: "Check out our new launch!",
      imageUrl: "https://example.com/ad.png",
      buttonText: "Check Now",
      actionUrl: "https://example.com/offer",
      isCancelable: true,
      showBannerAd: false,
      bannerText: "",
      bannerImageUrl: "",
      bannerActionUrl: "",
      updatedByUid: ADMIN_UID,
      updatedAt: ts,
    })
  );

  // Invalid popupType must fail validation
  await assertFails(
    adminDb.collection("app_config").doc("live_broadcast").set({
      showPopup: true,
      popupId: "popup_bad",
      popupType: "malware",
      version: "",
      forceUpdate: false,
      showOnce: true,
      title: "Bad",
      message: "Bad",
      imageUrl: "",
      buttonText: "Go",
      actionUrl: "",
      isCancelable: true,
      showBannerAd: false,
      bannerText: "",
      bannerImageUrl: "",
      bannerActionUrl: "",
      updatedByUid: ADMIN_UID,
      updatedAt: ts,
    })
  );

  // Non-admin CANNOT delete live_broadcast, Verified Admin CAN delete it
  await assertFails(aliceDb.collection("app_config").doc("live_broadcast").delete());
  await assertSucceeds(adminDb.collection("app_config").doc("live_broadcast").delete());
});


