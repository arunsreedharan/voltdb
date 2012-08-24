#!/usr/bin/python

import os, sys, re

# Path to eng checkout root directory. To use this as a git pre-commit hook,
# create a symlink to this file in .git/hooks with the name pre-commit
basepath = os.path.dirname(os.path.dirname(os.path.realpath(__file__))) + os.sep
ascommithook = False

prunelist = ('hsqldb19b3',
             'hsqldb',
             'jetty716',
             'proj_gen',
             'jni_md.h',
             'jni.h',
             'org_voltdb_jni_ExecutionEngine.h',
             'org_voltcore_utils_DBBPool.h',
             'org_voltcore_utils_DBBPool_DBBContainer.h',
             'simplejson',
             'projectfile',
             'deploymentfile',
             'xml',
             'helloworld',
             'jaxb',
             'pmsg')

def licenseStartsHere(content, approvedLicenses):
    for license in approvedLicenses:
        if content.startswith(license):
            return 1
    return 0

def verifyLicense(f, content, approvedLicensesJavaC, approvedLicensesPython):
    if f.endswith('.py'):
        if not content.startswith("#"):
            if content.lstrip().startswith("#"):
                print "ERROR: \"%s\" contains whitespace before initial comment." % f
                return 1
            else:
                print "ERROR: \"%s\" does not begin with a comment." % f
                return 1

        # skip hashbang
        if content.startswith("#!"):
            (ignore, content) = content.split("\n", 1)
            content = content.lstrip()

        # skip python coding magic
        if content.startswith("# -*-"):
            (ignore, content) = content.split("\n", 1)
            content = content.lstrip()

        # verify license
        if licenseStartsHere(content, approvedLicensesPython):
            return 0
        print "ERROR: \"%s\" does not start with an approved license." % f
    else:
        if not content.startswith("/*"):
            if content.lstrip().startswith("/*"):
                print "ERROR: \"%s\" contains whitespace before initial comment." % f
            else:
                print "ERROR: \"%s\" does not begin with a comment." % f
            return 1
        if licenseStartsHere(content, approvedLicensesJavaC):
            return 0
        print "ERROR: \"%s\" does not start with an approved license." % f
    return 1

def verifyTrailingWhitespace(f, content):
    if re.search(r'[\t\f\v ]\n', content):
        print("ERROR: \"%s\" contains trailing whitespace." % (f))
        return 1
    return 0

def verifyTabs(f, content):
    num = content.count('\t')
    if num  > 0:
        print("ERROR: \"%s\" contains %d tabs." % (f, num))
        return 1
    return 0

def verifySprintf(f, content):
    num = content.count('sprintf')
    if num > 0:
        print("ERROR: \"%s\" contains %d calls to sprintf(). Use snprintf()." % (f, num))
        return 1
    return 0

def verifyGetStringChars(f, content):
    if not (f.endswith('.cpp') or f.endswith('.c') or f.endswith('.h') or f.endswith('.hpp')):
        return 0
    num = content.count('GetStringChars')
    num += content.count('GetStringUTFChars')
    if num > 0:
        print("ERROR: \"%s\" contains %d calls to GetStringChars/GetStringUTFChars. These methods return invalid UTF-8 code points for some characters. You should do the encoding in Java and pass the string to native code as a byte array." % (f, num))
        return 1
    return 0

def readFile(filename):
    "read a file into a string"
    FH=open(filename, 'r')
    fileString = FH.read()
    FH.close()
    return fileString

def writeRepairedContent(filename, newtext, original):
    try:
        FH=open(filename + ".lcbak", 'r')
        FH.close()
    except IOError:
        FH=open(filename + ".lcbak", 'w')
        FH.write(original)
        FH.close()
    FH=open(filename, 'w')
    FH.write(newtext)
    FH.close()
    return newtext

def rmBakFile(filename):
    try:
        os.remove(filename + ".lcbak")
    except OSError:
        pass

def fixLicensePython(f, content, approvedLicensesPython):
    revisedcontent = content
    preserved = ""
    # skip hashbang
    if revisedcontent.startswith("#!"):
        (preserve, revisedcontent) = revisedcontent.split("\n", 1)
        preserved = preserved + preserve + "\n"

    # skip python coding magic
    if revisedcontent.startswith("# -*-"):
        (preserve, revisedcontent) = revisedcontent.split("\n", 1)
        preserved = preserved + preserve + "\n"

    if not revisedcontent.startswith("#"):
        if licenseStartsHere(revisedcontent.lstrip(), approvedLicensesPython):
            print "Fix: removing whitespace before the approved license."
            return writeRepairedContent(f, preserved + revisedcontent.lstrip(), content)

    print "Fix: Inserting a default license before the original content."
    return writeRepairedContent(f, preserved + approvedLicensesPython[-1] + revisedcontent, content)

def fixLicenseJavaC(f, content, approvedLicensesJavaC):
    if licenseStartsHere(content.lstrip(), approvedLicensesJavaC):
        print "Fix: removing whitespace before the approved license."
        revisedcontent = content.lstrip()
    else:
        print "Fix: Inserting a default license before the original content."
        revisedcontent = approvedLicensesJavaC[-1] + content
    return writeRepairedContent(f, revisedcontent,  content)

def fixTabs(f, content):
    cleanlines = []
    for line in content.split("\n"):
        while '\t' in line:
            (pre, post) = line.split('\t')
            # replace each tab with a complement of up to 4 spaces -- I suppose this could be made adjustable.
            # go ahead and allow trailing whitespace -- clean it up later
            line = pre + ("    "[(len(pre) % 4 ): 4]) + post
        cleanlines.append(line)
    print "Fix: Replacing tabs with 4th-column indentation."
    return writeRepairedContent(f, "\n".join(cleanlines),  content)

def fixTrailingWhitespace(f, content):
    lines = content.split("\n")
    cleanlines = []
    for line in lines:
        if re.search(r'[\t\f\v ]+$', line):
            (line, ignored) = re.split(r'[\t\f\v ]+$', line)
        cleanlines.append(line)
    print "Fix: Removing trailing whitespace."
    return writeRepairedContent(f, "\n".join(cleanlines),  content)


def processFile(f, fix, approvedLicensesJavaC, approvedLicensesPython): 
    for suffix in ('.java', '.cpp', '.cc', '.h', '.hpp', '.py'):
        if f.endswith(suffix):
            break
    else:
        return 0
    content = readFile(f)
    if fix:
        rmBakFile(f)
    result = 0

    retval = verifyLicense(f, content,  approvedLicensesJavaC, approvedLicensesPython)
    if retval != 0:
        if fix:
            if f.endswith('.py'):
                content = fixLicensePython(f, content, approvedLicensesPython)
            else:
                content = fixLicenseJavaC(f, content, approvedLicensesJavaC)
        result += retval

    retval = verifyTabs(f, content)
    if retval != 0:
        if fix:
            content = fixTabs(f, content)
        result += retval

    retval = verifyTrailingWhitespace(f, content)
    if (retval != 0):
        if fix:
            content = fixTrailingWhitespace(f, content)
        result += retval

    retval = verifySprintf(f, content)
    result += retval

    retval = verifyGetStringChars(f, content)
    result += retval

    return result

def processAllFiles(d, fix, approvedLicensesJavaC, approvedLicensesPython):
    files = os.listdir(d)
    errcount = 0
    for f in [f for f in files if not f.startswith('.') and f not in prunelist]:
        fullpath = os.path.join(d,f)
        if os.path.isdir(fullpath):
            errcount += processAllFiles(fullpath, fix, approvedLicensesJavaC, approvedLicensesPython)
        else:
            errcount += processFile(fullpath, fix, approvedLicensesJavaC, approvedLicensesPython)
    return errcount


fix = False
for arg in sys.argv[1:]:
    if arg == "--fix":
        fix = True

testLicenses =   [basepath + 'tools/approved_licenses/mit_x11_hstore_and_voltdb.txt',
                  basepath + 'tools/approved_licenses/mit_x11_evanjones_and_voltdb.txt',
                  basepath + 'tools/approved_licenses/mit_x11_michaelmccanna_and_voltdb.txt',
                  basepath + 'tools/approved_licenses/mit_x11_voltdb.txt']

srcLicenses =    [basepath + 'tools/approved_licenses/gpl3_hstore_and_voltdb.txt',
                  basepath + 'tools/approved_licenses/gpl3_evanjones_and_voltdb.txt',
                  basepath + 'tools/approved_licenses/gpl3_base64_and_voltdb.txt',
                  basepath + 'tools/approved_licenses/gpl3_voltdb.txt']

testLicensesPy = [basepath + 'tools/approved_licenses/mit_x11_voltdb_python.txt']

srcLicensesPy =  [basepath + 'tools/approved_licenses/gpl3_voltdb_python.txt']


errcount = 0
errcount += processAllFiles(basepath + "src", fix,
                            tuple([readFile(f) for f in srcLicenses]),
                            tuple([readFile(f) for f in srcLicensesPy]))

errcount += processAllFiles(basepath + "tests", fix,
                            tuple([readFile(f) for f in testLicenses]),
                            tuple([readFile(f) for f in testLicensesPy]))

errcount += processAllFiles(basepath + "examples", fix,
                            tuple([readFile(f) for f in testLicenses]),
                            tuple([readFile(f) for f in testLicensesPy]))

if errcount == 0:
    print "SUCCESS. Found 0 license text errors, 0 files containing tabs or trailing whitespace."
elif fix:
    print "PROGRESS? Found and tried to fix %d license text or whitespace errors. Re-run licensescheck to validate. Consult .lcbak files to recover if something went wrong." % errcount
else:
    print "FAILURE. Found %d license text or whitespace errors." % errcount

# run through any other source the caller wants checked
# assumes a single valid license in $repo/tools/approved_licenses/license.txt
# "${voltpro}" is the build.xml property - can be seen as a literal if the
# property is not set.
if not ascommithook:
    for arg in sys.argv[1:]:
        if arg == "--fix":
            fix = True
        elif arg != "${voltpro}":
            print "Checking additional repository: " + arg;
            proLicenses = ["../" + arg + '/tools/approved_licenses/license.txt']
            proLicensesPy = ["../" + arg + '/tools/approved_licenses/license_python.txt']
            errcount = 0
            errcount += processAllFiles("../" + arg + "/src/", fix,
                                        tuple([readFile(f) for f in proLicenses]),
                                        tuple([readFile(f) for f in proLicensesPy]))

            errcount += processAllFiles("../" + arg + "/tests/", fix,
                                        tuple([readFile(f) for f in proLicenses]),
                                        tuple([readFile(f) for f in proLicensesPy]))

            if errcount == 0:
                print "SUCCESS. Found 0 license text errors, 0 files containing tabs or trailing whitespace."
            else:
                print "FAILURE (%s). Found %d license text or whitespace errors." % (arg, errcount)



sys.exit(errcount)
