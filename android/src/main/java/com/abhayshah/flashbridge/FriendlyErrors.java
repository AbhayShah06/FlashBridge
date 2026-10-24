package com.abhayshah.flashbridge;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

final class FriendlyErrors {
    private FriendlyErrors() {}

    static final String OFFLINE = "Can't reach your PC. Connect the laptop to your phone's hotspot, then pair using the new PC address.";
    static final String FOLDER = "The PC transfer folder is unavailable. Choose a folder in the Windows app.";

    static String forPairCode(int code) {
        if (code == 403) return "Wrong pairing code. Enter the six digits shown on Windows.";
        if (code == 429) return "Too many pairing attempts. Wait one minute, then try again.";
        if (code == 404) return "The Windows app is out of date. Update it, then try again.";
        return "Pairing failed on the PC. Restart the Windows app and try again.";
    }

    static String forTransferCode(int code) {
        if (code == 401) return "Pairing expired. Pair with Windows again.";
        if (code == 503) return FOLDER;
        if (code == 404) return "The file is no longer in the PC transfer folder. Refresh the file list.";
        if (code == 507) return "The PC is out of storage space. Free up space, then retry.";
        return "The PC couldn't complete this transfer. Try again (error " + code + ").";
    }

    static String from(Exception error) {
        if (error instanceof ConnectException || error instanceof NoRouteToHostException
                || error instanceof UnknownHostException || error instanceof SocketTimeoutException) return OFFLINE;
        if (error instanceof SecurityException) return "File access expired. Select the file or folder again.";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "Something went wrong. Try again." : message;
    }
}
