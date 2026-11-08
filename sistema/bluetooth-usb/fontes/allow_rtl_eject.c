#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sepol/policydb/policydb.h>

static void fail(const char *message)
{
    fprintf(stderr, "%s\n", message);
    exit(EXIT_FAILURE);
}

static void allow_rule(policydb_t *policy, const char *from, const char *to,
                       const char *class_name, const char *const *requested, size_t count)
{
    type_datum_t *source = hashtab_search(policy->p_types.table, from);
    type_datum_t *target = hashtab_search(policy->p_types.table, to);
    class_datum_t *cls = hashtab_search(policy->p_classes.table, class_name);
    if (!source || !target || !cls)
        fail("required type or class is absent");

    avtab_key_t key = {
        .source_type = source->s.value,
        .target_type = target->s.value,
        .target_class = cls->s.value,
        .specified = AVTAB_ALLOWED,
    };
    avtab_datum_t added = {0};
    for (size_t i = 0; i < count; i++) {
        perm_datum_t *permission = hashtab_search(cls->permissions.table, requested[i]);
        if (!permission && cls->comdatum)
            permission = hashtab_search(cls->comdatum->permissions.table, requested[i]);
        if (!permission || permission->s.value < 1 || permission->s.value > 32)
            fail("required permission is absent or invalid");
        added.data |= UINT32_C(1) << (permission->s.value - 1);
    }
    avtab_datum_t *existing = avtab_search(&policy->te_avtab, &key);
    if (existing)
        existing->data |= added.data;
    else if (avtab_insert(&policy->te_avtab, &key, &added))
        fail("cannot insert the allow rule");
}

#define ALLOW(db, from, to, cls, ...) do { \
    const char *const permissions[] = {__VA_ARGS__}; \
    allow_rule(db, from, to, cls, permissions, sizeof(permissions) / sizeof(permissions[0])); \
} while (0)

int main(int argc, char **argv)
{
    if (argc != 3 && (argc != 4 ||
        (strcmp(argv[3], "bt-bridge") && strcmp(argv[3], "bt-boot"))))
        fail("usage: allow_rtl_eject ORIGINAL_POLICY OUTPUT_POLICY [bt-bridge|bt-boot]");

    FILE *input = fopen(argv[1], "rb");
    policydb_t policy;
    policy_file_t file;
    if (!input || policydb_init(&policy))
        fail("cannot open or initialize input policy");
    policy_file_init(&file);
    file.type = PF_USE_STDIO;
    file.fp = input;
    if (policydb_read(&policy, &file, 0))
        fail("cannot read original policy");
    fclose(input);

    const char *qti = "qti_init_shell";
    if (argc == 3) {
        ALLOW(&policy, qti, "sg_device", "chr_file", "getattr", "open", "read", "write", "ioctl");
    } else if (!strcmp(argv[3], "bt-bridge")) {
        ALLOW(&policy, "bluetooth", "su", "unix_stream_socket", "connectto");
    } else {
        ALLOW(&policy, "init", "system_file", "file", "mounton");
        ALLOW(&policy, "bluetooth", qti, "unix_stream_socket", "connectto");
        ALLOW(&policy, qti, qti, "socket", "create", "bind", "read", "write", "setopt", "ioctl", "getattr");
        ALLOW(&policy, qti, qti, "unix_stream_socket", "create", "bind", "listen", "accept", "read", "write", "getopt", "getattr");
        ALLOW(&policy, qti, qti, "capability", "net_admin", "net_raw");
    }

    FILE *output = fopen(argv[2], "wb");
    if (!output)
        fail("cannot open output policy");
    policy_file_init(&file);
    file.type = PF_USE_STDIO;
    file.fp = output;
    if (policydb_write(&policy, &file) || fclose(output))
        fail("cannot write patched policy");
    policydb_destroy(&policy);
    return EXIT_SUCCESS;
}
