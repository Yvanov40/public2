package fr.minilauncher;

import android.app.admin.DeviceAdminReceiver;

/**
 * Droit « administrateur de l'appareil » limité au verrouillage de l'écran (force-lock),
 * pour le geste « Verrouiller ». Rien d'autre n'est demandé.
 */
public final class LockAdmin extends DeviceAdminReceiver {
}
