
package com.kenai.jaffl;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

public abstract class Platform {
    private final OS os;
    private final CPU cpu;
    protected final Pattern libPattern;

    private static final class SingletonHolder {
        static final Platform PLATFORM = determinePlatform();
    }

    /**
     * Determines the operating system jffi is running on
     *
     * @return An member of the <tt>OS</tt> enum.
     */
    private static final OS determineOS() {
        String osName = System.getProperty("os.name").split(" ")[0].toLowerCase();
        if (osName.startsWith("mac") || osName.startsWith("darwin")) {
            return OS.DARWIN;
        } else if (osName.startsWith("linux")) {
            return OS.LINUX;
        } else if (osName.startsWith("sunos") || osName.startsWith("solaris")) {
            return OS.SOLARIS;
        } else if (osName.startsWith("aix")) {
            return OS.AIX;
        } else if (osName.startsWith("openbsd")) {
            return OS.OPENBSD;
        } else if (osName.startsWith("freebsd")) {
            return OS.FREEBSD;
        } else if (osName.startsWith("windows")) {
            return OS.WINDOWS;
        } else {
            return OS.UNKNOWN;
        }
    }

    /**
     * Determines the <tt>Platform</tt> that best describes the <tt>OS</tt>
     *
     * @param os The operating system.
     * @return An instance of <tt>Platform</tt>
     */
    private static final Platform determinePlatform(OS os) {
        switch (os) {
            case DARWIN:
                return new Darwin();
            case LINUX:
                return new Linux();
            case WINDOWS:
                return new Windows();
            case UNKNOWN:
                return new Unsupported(os);
            default:
                return new Default(os);
        }
    }

    private static final Platform determinePlatform() {
        String providerName = System.getProperty("jaffl.provider");
        try {
            Class c = Class.forName(providerName + "$Platform");
            return (Platform) c.newInstance();
        } catch (ClassNotFoundException ex) {
            return determinePlatform(determineOS());
        } catch (IllegalAccessException ex) {
            throw new ExceptionInInitializerError(ex);
        } catch (InstantiationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
    
    private static final CPU determineCPU() {
        String archString = System.getProperty("os.arch").toLowerCase();
        if ("x86".equals(archString) || "i386".equals(archString) || "i86pc".equals(archString)) {
            return CPU.I386;
        } else if ("x86_64".equals(archString) || "amd64".equals(archString)) {
            return CPU.X86_64;
        } else if ("ppc".equals(archString) || "powerpc".equals(archString)) {
            return CPU.PPC;
        }
        // Try to find by lookup up in the CPU list
        try {
            return CPU.valueOf(archString.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return CPU.UNKNOWN;
        }
    }

    public Platform(OS os, CPU cpu, String libPattern) {
        this.os = os;
        this.cpu = cpu;
        this.libPattern = Pattern.compile(libPattern);
    }
    
    private Platform(OS os) {
        this.os = os;
        this.cpu = determineCPU();
        int dataModel = Integer.getInteger("sun.arch.data.model");
        if (dataModel != 32 && dataModel != 64) {
            switch (cpu) {
                case I386:
                case PPC:
                case SPARC:
                    dataModel = 32;
                    break;
                case X86_64:
                case PPC64:
                case SPARCV9:
                case S390X:
                    dataModel = 64;
                    break;
                default:
                    throw new ExceptionInInitializerError("Cannot determine cpu address size");
            }
        }
        
        String libpattern = null;
        switch (os) {
            case WINDOWS:
                libpattern = ".*\\.dll$";
                break;
            case DARWIN:
                libpattern = "lib.*\\.(dylib|jnilib)$";
                break;
            default:
                libpattern = "lib.*\\.so.*$";
                break;
        }
        libPattern = Pattern.compile(libpattern);
    }
    
    /**
     * Gets the native <tt>Platform</tt>
     *
     * @return The current platform.
     */
    public static final Platform getNativePlatform() {
        return SingletonHolder.PLATFORM;
    }

    /**
     * Gets the current Operating System.
     *
     * @return A <tt>OS</tt> value representing the current Operating System.
     */
    public final OS getOS() {
        return os;
    }

    /**
     * Gets the current processor architecture the JVM is running on.
     *
     * @return A <tt>CPU</tt> value representing the current processor architecture.
     */
    public final CPU getCPU() {
        return cpu;
    }
    
    public final boolean isBSD() {
        return os == OS.FREEBSD || os == os.OPENBSD || os == OS.NETBSD || os == OS.DARWIN;
    }
    public final boolean isUnix() {
        return os != OS.WINDOWS;
    }

    /**
     * Gets the name of this <tt>Platform</tt>.
     *
     * @return The name of this platform.
     */
    public String getName() {
        return cpu + "-" + os;
    }

    /**
     * Maps from a generic library name (e.g. "c") to the platform specific library name.
     *
     * @param libName The library name to map
     * @return The mapped library name.
     */
    public String mapLibraryName(String libName) {
        //
        // A specific version was requested - use as is for search
        //
        if (libPattern.matcher(libName).find()) {
            return libName;
        }
        return System.mapLibraryName(libName);
    }

    /**
     * Searches through a list of directories for a native library.
     *
     * @param libName the base name (e.g. "c") of the library to locate
     * @param libraryPath the list of directories to search
     * @return the path of the library
     */
    public String locateLibrary(String libName, List<String> libraryPath) {
        String mappedName = mapLibraryName(libName);
        for (String path : libraryPath) {
            File libFile = new File(path, mappedName);
            if (libFile.exists()) {
                return libFile.getAbsolutePath();
            }
        }
        // Default to letting the system search for it
        return mappedName;
    }
    private static class Supported extends Platform {
        public Supported(OS os) {
            super(os);
        }
    }

    private static class Unsupported extends Platform {
        public Unsupported(OS os) {
            super(os);
        }
    }

    private static final class Default extends Supported {

        public Default(OS os) {
            super(os);
        }

    }
    /**
     * A {@link Platform} subclass representing the MacOS system.
     */
    private static final class Darwin extends Supported {

        public Darwin() {
            super(OS.DARWIN);
        }

        @Override
        public String mapLibraryName(String libName) {
            //
            // A specific version was requested - use as is for search
            //
            if (libPattern.matcher(libName).find()) {
                return libName;
            }
            return "lib" + libName + ".dylib";
        }
        
        @Override
        public String getName() {
            return "Darwin";
        }

    }
    /**
     * A {@link Platform} subclass representing the Linux operating system.
     */
    private static final class Linux extends Supported {

        public Linux() {
            super(OS.LINUX);
        }

        @Override
        public String locateLibrary(final String libName, List<String> libraryPath) {
            FilenameFilter filter = new FilenameFilter() {
                Pattern p = Pattern.compile("lib" + libName + "\\.so\\.[0-9]+$");
                String exact = "lib" + libName + ".so";
                public boolean accept(File dir, String name) {
                    return p.matcher(name).matches() || exact.equals(name);
                }
            };

            List<File> matches = new LinkedList<File>();
            for (String path : libraryPath) {
                File[] files = new File(path).listFiles(filter);
                if (files != null && files.length > 0) {
                    matches.addAll(Arrays.asList(files));
                }
            }

            //
            // Search through the results and return the highest numbered version
            // i.e. libc.so.6 is preferred over libc.so.5
            //
            int version = 0;
            String bestMatch = null;
            for (File file : matches) {
                String path = file.getAbsolutePath();
                if (bestMatch == null && path.endsWith(".so")) {
                    bestMatch = path;
                    version = 0;
                } else {
                    String num = path.substring(path.lastIndexOf(".so.") + 4);
                    try {
                        if (Integer.parseInt(num) >= version) {
                            bestMatch = path;
                        }
                    } catch (NumberFormatException e) {
                    } // Just skip if not a number
                }
            }
            return bestMatch != null ? bestMatch : mapLibraryName(libName);
        }
        @Override
        public String mapLibraryName(String libName) {
            // Older JDK on linux map 'c' to 'libc.so' which doesn't work
            return "c".equals(libName) || "libc.so".equals(libName)
                    ? "libc.so.6" : super.mapLibraryName(libName);
        }
    }

    /**
     * A {@link Platform} subclass representing the Windows system.
     */
    private static class Windows extends Supported {

        public Windows() {
            super(OS.WINDOWS);
        }
    }
}

