using System;
using CesiumLoader.SDK.Mods;
using CesiumOverlayMod;
using UnityEngine;

namespace SpeedRegression
{
    /// <summary>
    /// Minimal faithful stand-in for the bundled official SpeedHackController's Unity backend.
    /// The real controller has a private Apply(double) and only writes timeScale once its
    /// Unity backend is active; BuiltinActions discovers that method by reflection.
    /// </summary>
    public sealed class LegacyUnityController : ModBase
    {
        private readonly UnitySpeedScale _scale = new UnitySpeedScale(UnityScaleAccess.Read, UnityScaleAccess.Write);
        private double _target = 1;
        private bool _active;

        // Fault-injection knobs for the handoff tests: the real controller returns false
        // when its Unity backend failed, and OnUnload restores before the host drops it.
        public bool RejectApply;

        public override string Name => "LegacyUnityController";
        public override string Version => "2.5.4-test-double";

        public void Activate(double target)
        {
            _target = target;
            _active = true;
            _scale.Synchronize(target);
        }

        public bool IsActive => _active;

        // Same shape as SpeedHackController.Apply(double): private, one double parameter.
        private bool Apply(double speed)
        {
            _target = speed;
            if (RejectApply) return false;
            if (_active) _scale.Synchronize(_target);
            return true;
        }

        public override void OnUpdate()
        {
            // Official PollPerformance keeps re-asserting the Unity target while active.
            if (_active && UsesUnityMode()) _scale.Synchronize(_target);
        }

        public override void OnConfigChanged()
        {
            if (!UsesUnityMode())
            {
                _active = false;
                _scale.Restore();
                return;
            }
            if (_active) _scale.Synchronize(_target);
        }

        public override void OnUnload()
        {
            // The real controller restores its Unity scale on unload. ModHost.Stop calls
            // this before ctx.Mod=null, so a stopped controller can never keep writing.
            _active = false;
            _scale.Restore();
        }

        private bool UsesUnityMode()
        {
            return Config == null || string.Equals(Config.GetString("accelerationMode", "unity"), "unity", StringComparison.OrdinalIgnoreCase);
        }
    }
}
