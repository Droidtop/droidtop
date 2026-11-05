package com.winlator.xserver.extensions;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.XRequestError;

/**
 * "GLX" as a name only, for a session whose OpenGL is Mesa's xlib libGL (docs/SPEC.md 10b; Droidtop/tracker#309).
 *
 * That libGL does GLX on the client side and draws through plain X requests, so it never sends this server a GLX
 * request. winex11 still asks the server for the GLX extension first and turns OpenGL off for the whole process
 * when it is missing ("GLX extension is missing, disabling OpenGL"); the Android patch that skips the question
 * (WINE_X11FORCEGLX) is in some Wine builds and not others. Advertised, the name lets every build go on to the
 * client-side GLX. A real GLX request (from a libGL that speaks the protocol) gets BadImplementation.
 */
public class GLXExtension implements Extension {
    public static final byte MAJOR_OPCODE = -107;

    @Override
    public String getName() {
        return "GLX";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        throw new BadImplementation();
    }
}
