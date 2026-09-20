// Copyright 2025 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <jni.h>

#include <latch>
#include <mutex>
#include <vector>

#include <rcheevos/include/rc_client.h>

#include "Common/CommonTypes.h"
#include "Common/Config/Config.h"
#include "Common/Event.h"
#include "Common/HookableEvent.h"
#include "Core/AchievementManager.h"
#include "Core/Config/AchievementSettings.h"
#include "jni/AndroidCommon/AndroidCommon.h"
#include "jni/AndroidCommon/IDCache.h"

namespace {
    jstring ToJStringOrEmpty(JNIEnv *env, const char *str) {
        return ToJString(env, str ? str : "");
    }
}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_init(JNIEnv* env, jclass)
{
  AchievementManager::GetInstance().Init(nullptr);
}

JNIEXPORT jboolean JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_login(JNIEnv* env, jclass,
                                                                              jstring password)
{
  auto& instance = AchievementManager::GetInstance();
  bool success;
  std::latch login_complete_event{1};
  Common::EventHook login_hook =
      instance.login_event.Register([&login_complete_event, &success](int result) {
        success = (result == RC_OK);
        login_complete_event.count_down();
      });
  instance.Login(GetJString(env, password));
  login_complete_event.wait();
  return success;
}

JNIEXPORT void JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_logout(JNIEnv *env,
                                                                               jclass) {
    AchievementManager::GetInstance().Logout();
}

JNIEXPORT jboolean JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_isHardcoreModeActive(
    JNIEnv* env, jclass)
{
  return AchievementManager::GetInstance().IsHardcoreModeActive();
}

JNIEXPORT jboolean JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_isGameLoaded(JNIEnv *env,
                                                                                     jclass) {
    auto &instance = AchievementManager::GetInstance();
    std::lock_guard lg{instance.GetLock()};
    return Config::Get(Config::RA_ENABLED) && instance.HasAPIToken() && instance.IsGameLoaded();
}

JNIEXPORT jstring JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_getGameDisplayName(
        JNIEnv *env, jclass) {
    auto &instance = AchievementManager::GetInstance();
    std::lock_guard lg{instance.GetLock()};
    return ToJString(env, instance.GetGameDisplayName());
}

JNIEXPORT jintArray JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_getProgressValues(
        JNIEnv *env, jclass) {
    auto &instance = AchievementManager::GetInstance();
    std::lock_guard lg{instance.GetLock()};

    rc_client_user_game_summary_t totals{};
    if (instance.IsGameLoaded()) {
        rc_client_t *const client = instance.GetClient();

        if (rc_client_subset_list_t *const subsets = rc_client_create_subset_list(client)) {
            for (u32 i = 0; i < subsets->num_subsets; ++i) {
                // Each call zeroes the summary it is given, so the totals are accumulated separately.
                rc_client_user_game_summary_t summary{};
                rc_client_get_user_subset_summary(client, subsets->subsets[i]->id, &summary);

                totals.num_core_achievements += summary.num_core_achievements;
                totals.num_unlocked_achievements += summary.num_unlocked_achievements;
                totals.points_core += summary.points_core;
                totals.points_unlocked += summary.points_unlocked;
            }

            rc_client_destroy_subset_list(subsets);
        } else {
            rc_client_get_user_game_summary(client, &totals);
        }
    }

    constexpr jsize LENGTH = 4;
    const jint values[LENGTH] = {
            static_cast<jint>(totals.num_unlocked_achievements),
            static_cast<jint>(totals.num_core_achievements),
            static_cast<jint>(totals.points_unlocked),
            static_cast<jint>(totals.points_core),
    };

    jintArray result = env->NewIntArray(LENGTH);
    env->SetIntArrayRegion(result, 0, LENGTH, values);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_getSubsetProgressValues(
        JNIEnv *env, jclass, jint subset_id) {
    auto &instance = AchievementManager::GetInstance();
    std::lock_guard lg{instance.GetLock()};

    rc_client_user_game_summary_t summary{};
    if (instance.IsGameLoaded()) {
        rc_client_get_user_subset_summary(instance.GetClient(), static_cast<u32>(subset_id),
                                          &summary);
    }

    constexpr jsize LENGTH = 4;
    const jint values[LENGTH] = {
            static_cast<jint>(summary.num_unlocked_achievements),
            static_cast<jint>(summary.num_core_achievements),
            static_cast<jint>(summary.points_unlocked),
            static_cast<jint>(summary.points_core),
    };

    jintArray result = env->NewIntArray(LENGTH);
    env->SetIntArrayRegion(result, 0, LENGTH, values);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_getSubsetList(JNIEnv *env,
                                                                                      jclass) {
    auto &instance = AchievementManager::GetInstance();
    std::lock_guard lg{instance.GetLock()};

    const jclass subset_class = IDCache::GetAchievementSubsetClass();

    if (!instance.IsGameLoaded())
        return env->NewObjectArray(0, subset_class, nullptr);

    rc_client_subset_list_t *const list = rc_client_create_subset_list(instance.GetClient());
    if (!list)
        return env->NewObjectArray(0, subset_class, nullptr);

    jobjectArray result =
            env->NewObjectArray(static_cast<jsize>(list->num_subsets), subset_class, nullptr);

    for (u32 i = 0; i < list->num_subsets; ++i) {
        const rc_client_subset_t *const subset = list->subsets[i];

        jstring title = ToJStringOrEmpty(env, subset->title);
        jstring badge_url = ToJStringOrEmpty(env, subset->badge_url);
        jobject subset_obj = env->NewObject(subset_class,
                                            IDCache::GetAchievementSubsetConstructor(),
                                            static_cast<jint>(subset->id), title, badge_url);

        env->SetObjectArrayElement(result, static_cast<jsize>(i), subset_obj);
        env->DeleteLocalRef(subset_obj);
        env->DeleteLocalRef(badge_url);
        env->DeleteLocalRef(title);
    }

    rc_client_destroy_subset_list(list);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_getAchievementList(
        JNIEnv *env, jclass) {
    auto &instance = AchievementManager::GetInstance();
    std::lock_guard lg{instance.GetLock()};

    jclass achievement_class = IDCache::GetAchievementClass();

    if (!instance.IsGameLoaded())
        return env->NewObjectArray(0, achievement_class, nullptr);

    rc_client_achievement_list_t *list =
            rc_client_create_achievement_list(instance.GetClient(),
                                              RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE_AND_UNOFFICIAL,
                                              RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
    if (!list)
        return env->NewObjectArray(0, achievement_class, nullptr);

    jsize count = 0;
    for (u32 i = 0; i < list->num_buckets; ++i)
        count += static_cast<jsize>(list->buckets[i].num_achievements);

    jobjectArray result = env->NewObjectArray(count, achievement_class, nullptr);

    jsize index = 0;
    for (u32 i = 0; i < list->num_buckets; ++i) {
        const rc_client_achievement_bucket_t &bucket = list->buckets[i];
        jstring bucket_label = ToJStringOrEmpty(env, bucket.label);

        for (u32 j = 0; j < bucket.num_achievements; ++j) {
            const rc_client_achievement_t *achievement = bucket.achievements[j];

            jstring title = ToJStringOrEmpty(env, achievement->title);
            jstring description = ToJStringOrEmpty(env, achievement->description);
            jstring measured_progress = ToJStringOrEmpty(env, achievement->measured_progress);

            jobject achievement_obj = env->NewObject(
                    achievement_class, IDCache::GetAchievementConstructor(),
                    static_cast<jint>(achievement->id), title, description,
                    static_cast<jint>(achievement->points), static_cast<jint>(achievement->state),
                    measured_progress, bucket_label,
                    static_cast<jint>(achievement->type), static_cast<jint>(achievement->unlocked),
                    static_cast<jint>(bucket.subset_id));

            env->SetObjectArrayElement(result, index++, achievement_obj);

            // These add up fast on a large set, so don't wait for the frame to be popped.
            env->DeleteLocalRef(achievement_obj);
            env->DeleteLocalRef(measured_progress);
            env->DeleteLocalRef(description);
            env->DeleteLocalRef(title);
        }

        env->DeleteLocalRef(bucket_label);
    }

    rc_client_destroy_achievement_list(list);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_getAchievementBadgeValues(
        JNIEnv *env, jclass, jint id, jboolean locked) {
    auto &instance = AchievementManager::GetInstance();
    std::lock_guard lg{instance.GetLock()};

    const AchievementManager::Badge &badge = instance.GetAchievementBadge(
            static_cast<AchievementManager::AchievementId>(id), locked == JNI_TRUE);

    constexpr size_t HEADER_SIZE = 2;
    constexpr size_t BYTES_PER_PIXEL = 4;

    const size_t pixel_count = static_cast<size_t>(badge.width) * badge.height;
    if (pixel_count == 0 || badge.data.size() < pixel_count * BYTES_PER_PIXEL)
        return env->NewIntArray(0);

    std::vector<jint> values(HEADER_SIZE + pixel_count);
    values[0] = static_cast<jint>(badge.width);
    values[1] = static_cast<jint>(badge.height);

    // Badges are RGBA8, Android's ARGB_8888 wants 0xAARRGGBB.
    for (size_t i = 0; i < pixel_count; ++i) {
        const u8 *pixel = badge.data.data() + i * BYTES_PER_PIXEL;
        values[HEADER_SIZE + i] =
                static_cast<jint>((static_cast<u32>(pixel[3]) << 24) |
                                  (static_cast<u32>(pixel[0]) << 16) |
                                  (static_cast<u32>(pixel[1]) << 8) | static_cast<u32>(pixel[2]));
    }

    const auto length = static_cast<jsize>(values.size());
    jintArray result = env->NewIntArray(length);
    env->SetIntArrayRegion(result, 0, length, values.data());
    return result;
}

JNIEXPORT void JNICALL
Java_org_dolphinemu_dolphinemu_features_settings_model_AchievementModel_shutdown(JNIEnv* env,
                                                                                 jclass)
{
  AchievementManager::GetInstance().Shutdown();
}

}  // extern "C"
