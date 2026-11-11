# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
"""The help rule shared by all Skarph scripts: no arguments, help, -help, --help or -h print the help and nothing else
happens (exit code 0). Used by every script in tools/; the shell scripts follow the same rule."""
import sys

HELP_WORDS = ("help", "-help", "--help", "-h")


def wants_help(argv=None):
    """argv: the arguments WITHOUT the program name (default: sys.argv[1:])."""
    if argv is None:
        argv = sys.argv[1:]
    return len(argv) == 0 or argv[0] in HELP_WORDS


def run_help(parser, argv=None, subparsers=None):
    """Prints the help of an argparse parser and returns True when the arguments ask for it: none at all, or help in
    one of its spellings. With `subparsers` (the object that add_subparsers() returned) a sub command that is given
    without arguments, or followed by a help word, prints its own help."""
    if argv is None:
        argv = sys.argv[1:]
    if wants_help(argv):
        parser.print_help()
        return True
    if subparsers is not None and argv[0] in subparsers.choices and (len(argv) == 1 or argv[1] in HELP_WORDS):
        subparsers.choices[argv[0]].print_help()
        return True
    return False


def require(doc, *packages):
    """Imports the packages a script needs ("numpy", or "PIL:Pillow" = module:pip name) BEFORE the script's own imports.
    A missing package must not hide the help: with a help request the script's docstring is printed instead."""
    import importlib
    for spec in packages:
        module, _, pip_name = spec.partition(":")
        try:
            importlib.import_module(module)
        except ImportError:
            if wants_help():
                print(doc)
                sys.exit(0)
            print("error: this script needs the Python package %s (pip install %s)" % (module, pip_name or module),
                  file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    print(__doc__)
